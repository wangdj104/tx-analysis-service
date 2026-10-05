package org.familyhealthcare.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs only Spring's SQL reader against a recording JDBC proxy, never a database or launcher. */
class CarePlanBrowserFixtureEncodingTest {
    @TempDir Path temporary;
    @Test void realFixtureLoaderPreservesUnicodeWithNarrowedJavaEnvironment() throws Exception {
        String launcher=Files.readString(Path.of("src/test/java/org/familyhealthcare/service/CarePlanBrowserApplication.java"));
        Matcher statement=Pattern.compile("ScriptUtils\\.executeSqlScript\\(connection,[^;]+;").matcher(launcher);
        assertTrue(statement.find());
        String probe="import java.sql.*; import java.lang.reflect.*; import java.nio.charset.*; import org.springframework.jdbc.datasource.init.ScriptUtils; import org.springframework.core.io.ClassPathResource; import org.springframework.core.io.support.EncodedResource;\n"
          +"class FixtureEncodingProbe { public static void main(String[] args) throws Exception {"
          +"if(!Charset.defaultCharset().equals(StandardCharsets.US_ASCII))throw new AssertionError(\"Narrow encoding required\");"
          +"((ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(ScriptUtils.class)).setLevel(ch.qos.logback.classic.Level.OFF);"
          +"boolean[] found={false}; Statement s=(Statement)Proxy.newProxyInstance(FixtureEncodingProbe.class.getClassLoader(),new Class[]{Statement.class},(p,m,a)->{if(m.getName().equals(\"execute\")){if(((String)a[0]).contains(\"Synthetic \\u62a4\\u7406\\u539f\\u6587 <script>synthetic-report</script>\"))found[0]=true;return false;}if(m.getName().equals(\"getUpdateCount\"))return -1;return null;});"
          +"Connection connection=(Connection)Proxy.newProxyInstance(FixtureEncodingProbe.class.getClassLoader(),new Class[]{Connection.class},(p,m,a)->m.getName().equals(\"createStatement\")?s:null);"
          +statement.group()+" if(!found[0])throw new AssertionError(\"Fixture original Unicode was corrupted\"); System.out.print(\"UNICODE_PRESERVED\");}}";
        Path file=temporary.resolve("FixtureEncodingProbe.java");Files.writeString(file,probe,StandardCharsets.UTF_8);
        Path output=temporary.resolve("probe.txt");
        ProcessBuilder builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Dfile.encoding=US-ASCII","-cp",System.getProperty("java.class.path"),file.toString());
        builder.environment().keySet().removeIf(key->!key.equals("PATH")&&!key.equals("HOME"));
        Process process=builder.redirectErrorStream(true).redirectOutput(output.toFile()).start();
        boolean finished=process.waitFor(30,TimeUnit.SECONDS);if(!finished)process.destroyForcibly();
        assertTrue(finished,"Bounded encoding probe must terminate");
        assertEquals(0,process.exitValue(),Files.readString(output));
        assertTrue(Files.readString(output).contains("UNICODE_PRESERVED"));
    }
}
