package io.github.divinespear.maven.plugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

abstract class BaseProviderImpl
        implements SchemaGeneratorProvider {

    private final String providerName;

    public BaseProviderImpl(String providerName) {
        this.providerName = providerName;
    }

    @Override
    public String providerName() {
        return this.providerName;
    }

    @Override
    public void execute(ClassLoader classLoader,
                        JpaSchemaGeneratorMojo mojo) throws Exception {
        Thread thread = Thread.currentThread();
        synchronized (thread) {
            ClassLoader original = thread.getContextClassLoader();
            try {
                thread.setContextClassLoader(classLoader);
                this.doExecute(mojo);
            } finally {
                thread.setContextClassLoader(original);
            }

            // post-process: append semicolon(;) at end of line.
            this.appendSemiColonAtEol(new File(mojo.getOutputDirectory(), mojo.getCreateOutputFileName()));
            this.appendSemiColonAtEol(new File(mojo.getOutputDirectory(), mojo.getDropOutputFileName()));
        }
    }

    abstract protected void doExecute(JpaSchemaGeneratorMojo mojo);

    protected <K, V> Map<K, V> removeNullValuesFromMap(Map<? extends K, ? extends V> map) {
        Map<K, V> notNullMap = new HashMap<K, V>();
        for (Entry<? extends K, ? extends V> entry : map.entrySet()) {
            if (entry.getValue() != null) {
                notNullMap.put(entry.getKey(), entry.getValue());
            }
        }
        return notNullMap;
    }

    private void appendSemiColonAtEol(File file) throws IOException {
        this.appendSemiColonAtEol(file, Charset.defaultCharset().name());
    }

    private void appendSemiColonAtEol(File file,
                                      String charset) throws IOException {
        // file isn't exists
        if (!file.exists()) {
            return;
        }

        List<String> lines = new ArrayList<String>();

        // read and store semicolon(;) appended line
        BufferedReader reader = new BufferedReader(new InputStreamReader(new FileInputStream(file), charset));
        try {
            String line = null;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.endsWith(";")) {
                    line += ";";
                }
                lines.add(line);
            }
        } finally {
            reader.close();
        }

        // write processed lines
        PrintWriter writer = new PrintWriter(file, charset);
        try {
            for (String line : lines) {
                writer.println(line);
            }
        } finally {
            writer.close();
        }
    }
}
