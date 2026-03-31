import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import org.codehaus.janino.SimpleCompiler

def skillRoot = Paths.get(args ? args[0] : ".").toAbsolutePath().normalize()
def projectRoot = skillRoot.resolve("example")
def srcJava = projectRoot.resolve("src/main/java/com/example/quantrix/HelloPlugin.java")
def resourcesDir = projectRoot.resolve("src/main/resources")
def distDir = skillRoot.resolve("dist")
def jarPath = distDir.resolve("hello-quantrix-plugin.jar")

Files.createDirectories(distDir)

def compiler = new SimpleCompiler()
compiler.setParentClassLoader(Thread.currentThread().contextClassLoader)
compiler.setDebuggingInformation(true, true, true)
compiler.cook(srcJava.toString(), Files.newBufferedReader(srcJava))

def bytecodes = compiler.getBytecodes()

JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))
try {
    bytecodes.keySet().sort().each { className ->
        def entryName = className.replace('.', '/') + ".class"
        jar.putNextEntry(new JarEntry(entryName))
        jar.write(bytecodes.get(className))
        jar.closeEntry()
    }

    Files.list(resourcesDir).sorted().forEach { path ->
        jar.putNextEntry(new JarEntry(resourcesDir.relativize(path).toString()))
        jar.write(Files.readAllBytes(path))
        jar.closeEntry()
    }
} finally {
    jar.close()
}

println("Built ${jarPath}")
