package online.seuprojeto.filtrofanta

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object ShareApi {
    fun uploadPolaroid(jpeg: ByteArray, fileName: String) {
        val boundary = "Fanta${System.currentTimeMillis()}"
        val url = URL(BuildConfig.SHARE_ORIGIN.trimEnd('/') + "/upload_polaroid")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doInput = true
            doOutput = true
            connectTimeout = 8_000
            readTimeout = 12_000
            useCaches = false
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            conn.outputStream.use { out ->
                fun part(name: String, body: String) {
                    out.write(
                        ("--$boundary\r\nContent-Disposition: form-data; name=\"$name\"\r\n\r\n$body\r\n")
                            .toByteArray(Charsets.UTF_8),
                    )
                }
                part("name", fileName)
                out.write(
                    (
                        "--$boundary\r\n" +
                            "Content-Disposition: form-data; name=\"photo\"; filename=\"$fileName\"\r\n" +
                            "Content-Type: image/jpeg\r\n\r\n"
                        ).toByteArray(Charsets.UTF_8),
                )
                out.write(jpeg)
                out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                throw IOException("Servidor $code: ${body.take(180)}")
            }
        } finally {
            conn.disconnect()
        }
    }
}
