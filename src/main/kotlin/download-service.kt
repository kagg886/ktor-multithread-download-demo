import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToLong

@Serializable
data class DownloadService(
    val url: String,
    val contentLength: Long,
    val targetPath: String,
    private val blockCount: Int,
    private val bufferSize: Long
) {
    //rwd文件
    private val file: RandomAccessFile by lazy {
        RandomAccessFile(targetPath, "rwd")
    }

    //协程锁
    private val muteX by lazy {
        Mutex()
    }

    //分配的下载块大小
    private val blockSize by lazy {
        ceil(contentLength.toFloat() / blockCount).roundToLong()
    }

    //下载任务
    private val unCompleteTaskList = mutableListOf<DownloadTask>()

    //是否调用了init函数
    private val init
        get() = client != null

    //HTTP客户端
    @Transient
    private var client: HttpClient? = null

    //下载任务
    @Transient
    private var job: Job? = null

    //已经下载的大小
    val downloadSize: Long
        get() = unCompleteTaskList.sumOf { it.downloadSize }

    //已经下载的进度
    val progress
        get() = downloadSize.toFloat() / contentLength

    //是否正在下载
    val isDownloading
        get() = job != null

    fun init(client: HttpClient) {
        check(!init) {
            "service already init"
        }
        this.client = client
        if (unCompleteTaskList.isEmpty()) {
            unCompleteTaskList.addAll(
                //Range是闭区间，step值需要比blockSize大1以避免上一个块的末尾和下一个块的头重复
                (0..<contentLength step blockSize + 1).map {
                    val start = it
                    val end = min(it + blockSize, contentLength)
                    DownloadTask(
                        start = start,
                        end = end,
                        downloadSize = 0
                    )
                }
            )
        }
    }

    fun start(scope: CoroutineScope, block: HttpRequestBuilder.() -> Unit = {}) {
        //检查是否初始化
        check(init) {
            "service not init"
        }
        //检查是否有别的下载任务
        check(job == null) {
            "download still in progress"
        }
        //启动一个协程
        job = scope.launch {
            //筛选出未下载完成的切片
            unCompleteTaskList.filter { it.downloadSize <= blockSize }.map { taskConfig ->
                //启动子协程，在子协程中查询文件
                launch {
                    //使用prepareGet而不是get，避免ktor帮我们将body下载好。
                    val statement = client!!.prepareGet(url) {
                        block()
                        headers {
                            //Range头，通过这样的格式获取切片数据
                            append("Range", "bytes=${taskConfig.start + taskConfig.downloadSize}-${taskConfig.end}")
                        }
                    }
                    //开始执行请求
                    statement.execute {
                        //获取channel
                        val channel = it.bodyAsChannel()
                        while (true) {
                            //读取最多bufferSize的字节，若无读取内容则返回空数组。此时证明切片下载完毕。
                            val bytes = channel.readRemaining(bufferSize).readBytes()
                            if (bytes.isEmpty()) {
                                break
                            }
                            //RandomAccessFile的seek函数非线程安全。
                            muteX.withLock {
                                //IO操作需要转移到别的线程中运行。
                                withContext(Dispatchers.IO) {
                                    file.seek(taskConfig.start + taskConfig.downloadSize)
                                    file.write(bytes, 0, bytes.size)
                                }
                                taskConfig.downloadSize += bytes.size
                            }
                        }
                    }
                }
            }.joinAll() //等待全部任务下载完成，然后将job置为null代表下载任务结束。
            job = null
        }
    }

    suspend fun await() {
        job?.join() ?: throw IllegalStateException("任务未开始")
    }

    fun pause() {
        check(init) {
            "service not init"
        }
        check(job != null) {
            "download not started"
        }
        job!!.cancel()
        job = null
    }

    companion object {
        suspend fun create(
            block: DownloadServiceConfig.() -> Unit
        ): DownloadService {
            val config = DownloadServiceConfig().apply(block)

            val client = HttpClient(CIO) {
                install(HttpTimeout)
            }
            val url = config.url
            val response = client.head(url) {
                headers.append("Accept-Ranges", "acceptable-ranges")
            }.call.response.headers

            check(response["Accept-Ranges"] == "bytes") {
                client.close()
                "$url not support multi-thread download"
            }
            val contentLength = response["Content-Length"]?.toLongOrNull()
            check(contentLength != null && contentLength > 0) {
                client.close()
                "Content Length is Null"
            }
            client.close()
            return DownloadService(
                url = url,
                contentLength = contentLength,
                targetPath = config.targetPath.absolutePath,
                blockCount = config.blockCount,
                bufferSize = config.bufferedSize
            )
        }
    }
}

class DownloadServiceConfig {
    //下载地址
    lateinit var url: String
    //目标文件
    lateinit var targetPath: File
    //分块数
    var blockCount: Int = 16
    var bufferedSize: Long = 1024
}

@Serializable
data class DownloadTask(
    var start: Long,
    var end: Long,
    var downloadSize: Long = 0
)
