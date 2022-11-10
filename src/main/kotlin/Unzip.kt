// https://qiita.com/jim/items/2c0b0a0acacd78f49b49

import java.io.BufferedOutputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

// 最大ファイル数
private const val unzipMaxEntries: Int = 1024 * 5
// 単体の最大ファイルサイズ
private const val unzipMaxFileSize: Long = 1024L * 1024L * 1024L * 5L // 5GiB
// 全ファイルの合計最大ファイルサイズ
private const val unzipMaxTotalSize: Long = 1024L * 1024L * 1024L * 5L // 5GiB
// バッファサイズ (参考値: https://gihyo.jp/admin/clip/01/fdt/200810/31)
private const val unzipBufferSize: Int = 1024 * 1024 // 1MiB


fun unzipFile(targetFilePath: Path, destDirPath: Path = targetFilePath.parent, zipFileCoding: Charset = Charset.forName("Shift_JIS")) {
    ZipInputStream(Files.newInputStream(targetFilePath), zipFileCoding).use { f ->
        var zipEntry: ZipEntry?
        var nEntries = 0
        var totalReads = 0L
        val buffer = ByteArray(unzipBufferSize)
        while (f.nextEntry.also { zipEntry = it } != null) {
            val entryPath = Paths.get(zipEntry!!.name).normalize()
            if(entryPath.startsWith("__MACOSX")){
                continue
            }
            if(zipEntry?.isDirectory == false && zipEntry?.name?.endsWith(".class") == true)
                continue
            if (entryPath.startsWith(Paths.get(".."))) {
                throw IllegalStateException("File is outside extraction target directory.")
            }
            if (nEntries++ >= unzipMaxEntries) {
                throw IllegalStateException("Too many files to unzip.")
            }

            val dst = destDirPath.resolve(entryPath)
            if (zipEntry!!.isDirectory) {
                Files.createDirectories(dst)
            } else {
                // System.err.println("inflating: $dst")
                Files.createDirectories(dst.parent)

                var totalFileReads = 0L
                var nReads: Int
                FileOutputStream(dst.toFile()).use { fos ->
                    BufferedOutputStream(fos).use { out ->
                        while (f.read(buffer, 0, buffer.size).also { nReads = it } != -1) {
                            totalReads += nReads
                            if (totalReads > unzipMaxTotalSize) {
                                throw IllegalStateException("Total file size being unzipped is too big.")
                            }
                            totalFileReads += nReads
                            if (totalFileReads > unzipMaxFileSize) {
                                throw IllegalStateException("File being unzipped is too big.")
                            }
                            out.write(buffer, 0, nReads)
                        }
                        out.flush()
                    }
                }
                f.closeEntry()
            }
        }
    }
}