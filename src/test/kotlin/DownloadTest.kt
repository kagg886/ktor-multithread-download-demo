import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.util.cio.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletionException
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class DownloadTest {
    @Test
    fun `should be download success`(): Unit = runBlocking {
        //实例化CIO客户端
        val client = HttpClient(CIO) {
            install(HttpTimeout) {
                //下载文件需要多给点时间给传输层。
                requestTimeoutMillis = 30.minutes.inWholeMicroseconds
                connectTimeoutMillis = 30.minutes.inWholeMicroseconds
                socketTimeoutMillis = 30.minutes.inWholeMicroseconds
            }
        }

        val target = File("node-v18.20.4-win-x64.zip").apply {
            if (exists()) {
                delete()
            }
            //仅在第一次时初始化文件，若文件被删除可抛出异常
            absoluteFile.parentFile.mkdirs()
            createNewFile()
        }

        //创建下载任务
        val service = DownloadService.create {
            url = "https://registry.npmmirror.com/-/binary/node/latest-v18.x/node-v18.20.4-win-x64.zip"
            targetPath = target
        }

        //绑定下载任务到客户端
        service.init(client)
        //开始下载
        service.start(this)

        launch {
            //是否下载完成
            while (service.isDownloading) {
                delay(1.seconds)
                with(service) {
                    println("download: $downloadSize --- $contentLength (${progress})")
                }
            }
            println("下载完成")
        }
        service.await()
        assertEquals("a2864d9048fb83cc85e3b2c3d18f5731b69cae8964bb029f5cdecbb0820eccd7",target.sha256().lowercase())
    }
}

suspend fun File.sha256(): String {
    val messageDigest = MessageDigest.getInstance("SHA-1")
    while (true) {
        val byt = this.readChannel().readRemaining(1024).readBytes()
        if (byt.isEmpty()) {
            break
        }
        messageDigest.update(byt)
    }
    return messageDigest.digest().joinToString("") { "%02x".format(it) }
}