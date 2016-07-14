/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package io.github.divinespear.maven.plugin;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.*;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.springframework.orm.jpa.persistenceunit.DefaultPersistenceUnitManager;
import org.springframework.orm.jpa.persistenceunit.SmartPersistenceUnitInfo;

import javax.persistence.spi.PersistenceProvider;
import java.io.*;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.DatabaseMetaData;
import java.sql.Driver;
import java.sql.DriverManager;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Generate database schema or DDL scripts.
 *
 * @author divinespear
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.PROCESS_CLASSES, inheritByDefault = false,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class JpaSchemaGeneratorMojo extends AbstractMojo {

    private static final Map<String, String> LINE_SEPARATOR_MAP = new HashMap<>();
    private static final Map<Vendor, String> PROVIDER_MAP = new HashMap<>();
    private static final Pattern CREATE_DROP_PATTERN = Pattern.compile("((?:create|drop|alter)\\s+(?:table|view|sequence))",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern PATTERN_CREATE_TABLE = Pattern.compile("(?i)^create(\\s+\\S+)?\\s+(?:table|view)"),
            PATTERN_CREATE_INDEX = Pattern.compile("(?i)^create(\\s+\\S+)?\\s+index"),
            PATTERN_ALTER_TABLE = Pattern.compile("(?i)^alter\\s+table");

    static {
        LINE_SEPARATOR_MAP.put("CR", "\r");
        LINE_SEPARATOR_MAP.put("LF", "\n");
        LINE_SEPARATOR_MAP.put("CRLF", "\r\n");
    }

    static {
        PROVIDER_MAP.put(Vendor.eclipselink, "org.eclipse.persistence.jpa.PersistenceProvider");
        PROVIDER_MAP.put(Vendor.hibernate, "org.hibernate.jpa.HibernatePersistenceProvider");
    }

    private final Log log = this.getLog();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;
    @Parameter(defaultValue = "${plugin}", readonly = true, required = true)
    private PluginDescriptor pluginDescriptor;
    @Parameter(defaultValue = "${session}")
    private MavenSession session;
    @Component
    private RepositorySystem repoSystem;

    /**
     * skip schema generation
     */
    @Parameter(property = "jpa-schema.generate.skip", required = true, defaultValue = "false")
    private boolean skip = false;
    /**
     * generate as formatted
     */
    @Parameter(property = "jpa-schema.generate.format", required = true, defaultValue = "false")
    private boolean format = false;
    /**
     * scan test classes
     */
    @Parameter(property = "jpa-schema.generate.scan-test-classes", required = true, defaultValue = "false")
    private boolean scanTestClasses = false;
    /**
     * location of {@code persistence.xml} file
     * <p>
     * Note for Hibernate <b>DOES NOT SUPPORT custom location.</b> ({@link SchemaExport} support it, but JPA 2.1 schema
     * generator does NOT.)
     */
    @Parameter(required = true, defaultValue = JpaSchemaGeneratorUtils.ECLIPSELINK_PERSISTENCE_XML_DEFAULT)
    private String persistenceXml = JpaSchemaGeneratorUtils.ECLIPSELINK_PERSISTENCE_XML_DEFAULT;
    /**
     * unit name of {@code persistence.xml}
     */
    @Parameter(required = true, defaultValue = "default")
    private String persistenceUnitName = "default";
    /**
     * schema generation action for database
     * <p>
     * support value is {@code none}, {@code create}, {@code drop}, {@code drop-and-create}, or
     * {@code create-or-extend-tables}.
     * <p>
     * {@code create-or-extend-tables} only support for EclipseLink with database target.
     */
    @Parameter(required = true, defaultValue = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_NONE_ACTION)
    private String databaseAction = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_NONE_ACTION;
    /**
     * schema generation action for script
     * <p>
     * support value is {@code none}, {@code create}, {@code drop}, or {@code drop-and-create}.
     */
    @Parameter(required = true, defaultValue = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_NONE_ACTION)
    private String scriptAction = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_NONE_ACTION;
    /**
     * output directory for generated ddl scripts
     * <p>
     * REQUIRED for {@link #scriptAction} is one of {@code create}, {@code drop}, or
     * {@code drop-and-create}.
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-schema")
    private File outputDirectory;
    /**
     * generated create script name
     * <p>
     * REQUIRED for {@link #scriptAction} is one of {@code create}, or {@code drop-and-create}.
     */
    @Parameter(defaultValue = "create.sql")
    private String createOutputFileName = "create.sql";
    /**
     * generated drop script name
     * <p>
     * REQUIRED for {@link #scriptAction} is one of {@code drop}, or {@code drop-and-create}.
     */
    @Parameter(defaultValue = "drop.sql")
    private String dropOutputFileName = "drop.sql";
    /**
     * specifies whether the creation of database artifacts is to occur on the basis of the object/relational mapping
     * metadata, DDL script, or a combination of the two.
     * <p>
     * support value is {@code metadata}, {@code script}, {@code metadata-then-script}, or
     * {@code script-then-metadata}.
     */
    @Parameter(defaultValue = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_METADATA_SOURCE)
    private String createSourceMode = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_METADATA_SOURCE;
    /**
     * create source file path.
     * <p>
     * REQUIRED for {@link #createSourceMode} is one of {@code script}, {@code metadata-then-script}, or
     * {@code script-then-metadata}.
     */
    @Parameter
    private File createSourceFile;
    /**
     * specifies whether the dropping of database artifacts is to occur on the basis of the object/relational mapping
     * metadata, DDL script, or a combination of the two.
     * <p>
     * support value is {@code metadata}, {@code script}, {@code metadata-then-script}, or
     * {@code script-then-metadata}.
     */
    @Parameter(defaultValue = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_METADATA_SOURCE)
    private String dropSourceMode = JpaSchemaGeneratorUtils.SCHEMA_GENERATION_METADATA_SOURCE;
    /**
     * drop source file path.
     * <p>
     * REQUIRED for {@link #dropSourceMode} is one of {@code script}, {@code metadata-then-script}, or
     * {@code script-then-metadata}.
     */
    @Parameter
    private File dropSourceFile;
    /**
     * jdbc driver class name
     * <p>
     * default is declared class name in persistence xml.
     * <p>
     * and Remember, <strike><a href="http://callofduty.wikia.com/wiki/No_Russian" target="_blank">No
     * Russian</a></strike> you MUST configure jdbc driver as plugin's dependency.
     */
    @Parameter
    private String jdbcDriver;
    /**
     * jdbc connection url
     * <p>
     * default is declared connection url in persistence xml.
     */
    @Parameter
    private String jdbcUrl;
    /**
     * jdbc connection username
     * <p>
     * default is declared username in persistence xml.
     */
    @Parameter
    private String jdbcUser;
    /**
     * jdbc connection password
     * <p>
     * default is declared password in persistence xml.
     * <p>
     * If your account has no password (especially local file-base, like Apache Derby, H2, etc...), it can be omitted.
     */
    @Parameter
    private String jdbcPassword;
    /**
     * database product name for emulate database connection. this should useful for script-only action.
     * <ul>
     * <li>specified if scripts are to be generated by the persistence provider and a connection to the target database
     * is not supplied.</li>
     * <li>The value of this property should be the value returned for the target database by
     * {@link DatabaseMetaData#getDatabaseProductName()}</li>
     * </ul>
     */
    @Parameter
    private String databaseProductName;
    /**
     * database major version for emulate database connection. this should useful for script-only action.
     * <ul>
     * <li>specified if sufficient database version information is not included from
     * {@link DatabaseMetaData#getDatabaseProductName()}</li>
     * <li>The value of this property should be the value returned for the target database by
     * {@link DatabaseMetaData#getDatabaseMajorVersion()}</li>
     * </ul>
     */
    @Parameter
    private Integer databaseMajorVersion;
    /**
     * database minor version for emulate database connection. this should useful for script-only action.
     * <ul>
     * <li>specified if sufficient database version information is not included from
     * {@link DatabaseMetaData#getDatabaseProductName()}</li>
     * <li>The value of this property should be the value returned for the target database by
     * {@link DatabaseMetaData#getDatabaseMinorVersion()}</li>
     * </ul>
     */
    @Parameter
    private Integer databaseMinorVersion;
    /**
     * line separator for generated schema file.
     * <p>
     * support value is one of {@code CRLF} (windows default), {@code LF} (*nix, max osx), and {@code CR}
     * (classic mac), in case-insensitive.
     * <p>
     * default value is system property {@code line.separator}. if JVM cannot detect {@code line.separator},
     * then use {@code LF} by <a href="http://git-scm.com/book/en/Customizing-Git-Git-Configuration">git
     * {@code core.autocrlf} handling</a>.
     */
    @Parameter
    private String lineSeparator = System.getProperty("line.separator", "\n");
    /**
     * JPA vendor specific properties.
     */
    @Parameter
    private Map<String, String> properties = new HashMap<>();
    /**
     * JPA vendor name or class name of vendor's {@link PersistenceProvider} implemention.
     * <p>
     * vendor name is one of
     * <ul>
     * <li>{@code eclipselink}</li>
     * <li>{@code hibernate}</li>
     * </ul>
     * <p>
     * <b>REQUIRED for project without {@code persistence.xml}</b>
     */
    @Parameter
    private Vendor vendor;
    /**
     * list of package name for scan entity classes
     * <p>
     * <b>REQUIRED for project without {@code persistence.xml}</b>
     */
    @Parameter
    private List<String> packageToScan = new ArrayList<>();
    /**
     * validation mode value.
     * <p>
     * {@code AUTO}. If Validation provider is in a scope, validation constraints are also used in schema generation.
     */
    @Parameter
    private ValidationMode validationMode;

    @SuppressWarnings("unused")
    public boolean isSkip() {
        return skip;
    }

    public boolean isFormat() {
        return format;
    }

    @SuppressWarnings("unused")
    public boolean isScanTestClasses() {
        return scanTestClasses;
    }

    public String getPersistenceXml() {
        return persistenceXml;
    }

    @SuppressWarnings("unused")
    public String getPersistenceUnitName() {
        return persistenceUnitName;
    }

    public String getDatabaseAction() {
        return databaseAction;
    }

    public String getScriptAction() {
        return scriptAction;
    }

    public File getOutputDirectory() {
        return outputDirectory;
    }

    @SuppressWarnings("unused")
    public String getCreateOutputFileName() {
        return createOutputFileName;
    }

    public File getCreateOutputFile() {
        return this.outputDirectory == null ? null : new File(this.outputDirectory, this.createOutputFileName);
    }

    @SuppressWarnings("unused")
    public String getDropOutputFileName() {
        return dropOutputFileName;
    }

    public File getDropOutputFile() {
        return this.outputDirectory == null ? null : new File(this.outputDirectory, this.dropOutputFileName);
    }

    public String getCreateSourceMode() {
        return createSourceMode;
    }

    public File getCreateSourceFile() {
        return createSourceFile;
    }

    public String getDropSourceMode() {
        return dropSourceMode;
    }

    public File getDropSourceFile() {
        return dropSourceFile;
    }

    public String getJdbcDriver() {
        return jdbcDriver;
    }

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public String getJdbcUser() {
        return jdbcUser;
    }

    public String getJdbcPassword() {
        return jdbcPassword;
    }

    public String getDatabaseProductName() {
        return databaseProductName;
    }

    public Integer getDatabaseMajorVersion() {
        return databaseMajorVersion;
    }

    public Integer getDatabaseMinorVersion() {
        return databaseMinorVersion;
    }

    public String getLineSeparator() {
        String actual = StringUtils.isEmpty(lineSeparator) ? null : LINE_SEPARATOR_MAP.get(lineSeparator.toUpperCase());
        return actual == null ? System.getProperty("line.separator", "\n") : actual;
    }

    public Map<String, String> getProperties() {
        return properties;
    }

    public Vendor getVendor() {
        return vendor;
    }

    public String getProviderClassName() {
        return PROVIDER_MAP.get(vendor);
    }

    public List<String> getPackagesToScan() {
        return packageToScan;
    }

    public ValidationMode getValidationMode() {
        return validationMode;
    }

    private ClassLoader getProjectClassLoader() throws MojoExecutionException {
        try {
            // compiled classes and dependencies
            List<String> classFiles = this.project.getCompileClasspathElements();
            if (this.scanTestClasses) {
                classFiles.addAll(this.project.getTestClasspathElements());
            }
            // classpath to url
            List<URL> classURLs = new ArrayList<>(classFiles.size());
            for (String classfile : classFiles) {
                classURLs.add(new File(classfile).toURI().toURL());
            }

            // add custom plugin dependencies
            classURLs.addAll(resolvePluginDependencies());

            // add plugin itself
            classURLs.add(PersistenceUnitInfoImp.class.getProtectionDomain().getCodeSource().getLocation());

            // display classpath
            for (URL url : classURLs) {
                this.log.info("  * classpath: " + url);
            }

            return new URLClassLoader(classURLs.toArray(new URL[classURLs.size()]), this.getClass().getClassLoader().getParent());
        } catch (Exception e) {
            this.log.error("Error while creating classloader", e);
            throw new MojoExecutionException("Error while creating classloader", e);
        }
    }

    private List<URL> resolvePluginDependencies() throws DependencyResolutionException, MalformedURLException {
        List<URL> result = new ArrayList<>();

        if (pluginDescriptor.getPlugin().getDependencies() != null) {
            RepositorySystemSession repositorySession = session.getRepositorySession();

            CollectRequest collectRequest = new CollectRequest();
            collectRequest.setRepositories(project.getRemoteProjectRepositories());
            for (Dependency dependency : pluginDescriptor.getPlugin().getDependencies()) {
                org.eclipse.aether.graph.Dependency pluginDep =
                        RepositoryUtils.toDependency(dependency, repositorySession.getArtifactTypeRegistry());
                collectRequest.addDependency(pluginDep);
            }

            DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
            for (ArtifactResult artifactResult : repoSystem.resolveDependencies(repositorySession, dependencyRequest).getArtifactResults())
                result.add(artifactResult.getArtifact().getFile().toURI().toURL());
        }

        return result;
    }

    private void generate() throws Exception {
        Map<String, Object> map = JpaSchemaGeneratorUtils.buildProperties(this);

        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        Class<?> jpaSchemaGeneratorWrapperClass = loader.loadClass("io.github.divinespear.maven.plugin.JpaSchemaGeneratorWrapper");
        Constructor constructor;
        Object jpaSchemaGeneratorWrapper;
        if (getVendor() == null) {
            // with persistence.xml
            constructor = jpaSchemaGeneratorWrapperClass.getDeclaredConstructor(String.class, Map.class);
            constructor.setAccessible(true);
            jpaSchemaGeneratorWrapper = constructor.newInstance(this.persistenceUnitName, map);
        } else {
            List<String> packages = getPackagesToScan();
            if (packages.isEmpty()) {
                throw new IllegalArgumentException("packageToScan is required on xml-less mode.");
            }

            DefaultPersistenceUnitManager manager = new DefaultPersistenceUnitManager();
            manager.setPackagesToScan(packages.toArray(new String[packages.size()]));
            manager.afterPropertiesSet();

            SmartPersistenceUnitInfo info = (SmartPersistenceUnitInfo) manager.obtainDefaultPersistenceUnitInfo();

            constructor = jpaSchemaGeneratorWrapperClass.getDeclaredConstructor(String.class, URL.class, List.class, Map.class);
            constructor.setAccessible(true);
            jpaSchemaGeneratorWrapper = constructor.newInstance(getProviderClassName(), info.getPersistenceUnitRootUrl(), info.getManagedClassNames(), map);
        }

        Method method = jpaSchemaGeneratorWrapperClass.getDeclaredMethod("generateSchema");
        method.setAccessible(true);
        try {
            method.invoke(jpaSchemaGeneratorWrapper);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getTargetException();
        }
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.skip) {
            log.info("schema generation is skipped.");
            return;
        }

        if (this.outputDirectory != null && !this.outputDirectory.exists()) {
            //noinspection ResultOfMethodCallIgnored
            this.outputDirectory.mkdirs();
        }

        final ClassLoader classLoader = this.getProjectClassLoader();
        // driver load hack
        // http://stackoverflow.com/questions/288828/how-to-use-a-jdbc-driver-from-an-arbitrary-location
        if (StringUtils.isNotBlank(this.jdbcDriver)) {
            try {
                Driver driver = (Driver) classLoader.loadClass(this.jdbcDriver).newInstance();
                DriverManager.registerDriver(driver);
            } catch (Exception e) {
                throw new MojoExecutionException("Dependency for driver-class " + this.jdbcDriver + " is missing!", e);
            }
        }

        // generate schema
        Thread thread = Thread.currentThread();
        ClassLoader currentClassLoader = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(classLoader);
            this.generate();
        } catch (Exception e) {
            throw new MojoExecutionException("Error while running", e);
        } finally {
            thread.setContextClassLoader(currentClassLoader);
        }

        // post-process
        try {
            this.postProcess();
        } catch (IOException e) {
            throw new MojoExecutionException("Error while post-processing script file", e);
        }
    }

    private void postProcess() throws IOException {
        final String linesep = this.getLineSeparator();

        List<File> files = Arrays.asList(this.getCreateOutputFile(), this.getDropOutputFile());
        for (File file : files) {
            // check file exists
            if (file == null || !file.exists()) {
                continue;
            }
            File tempFile = File.createTempFile("script", null, this.getOutputDirectory());
            // read/write with eol
            try (BufferedReader reader = new BufferedReader(new FileReader(file));
                 PrintWriter writer = new PrintWriter(tempFile)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    line = CREATE_DROP_PATTERN.matcher(line).replaceAll(";$1");
                    for (String s : line.split(";")) {
                        if (StringUtils.isBlank(s)) {
                            continue;
                        }
                        s = s.trim();
                        writer.print((this.isFormat() ? format(s) : s).replaceAll("\r\n", linesep));
                        writer.print(";");
                        writer.print(linesep);
                        writer.print(this.isFormat() ? linesep : "");
                    }
                }
                writer.flush();
            } finally {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                //noinspection ResultOfMethodCallIgnored
                tempFile.renameTo(file);
            }
        }

    }

    String format(String s) {
        final String linesep = this.getLineSeparator();

        s = s.replaceAll("^([^(]+\\()", "$1\r\n\t")
                .replaceAll("\\)[^()]*$", "\r\n$0")
                .replaceAll("((?:[^(),\\s]+|\\S\\([^)]+\\)[^),]*),)\\s*", "$1\r\n\t");
        StringBuilder builder = new StringBuilder();
        boolean completed = true;
        if (PATTERN_CREATE_TABLE.matcher(s).find()) {
            for (String it : s.split("\r\n")) {
                if (it.matches("^\\S.*$")) {
                    if (!completed) {
                        builder.append(linesep);
                        completed = true;
                    }
                    builder.append(it).append(linesep);
                } else if (completed) {
                    if (it.matches("^\\s*[^(]+(?:[^(),\\s]+|\\S\\([^)]+\\)[^),]*),\\s*$")) {
                        builder.append(it).append(linesep);
                    } else {
                        builder.append(it);
                        completed = false;
                    }
                } else {
                    builder.append(it.trim());
                    if (it.matches("[^)]+\\).*$")) {
                        builder.append(linesep);
                        completed = true;
                    }
                }
            }
        } else if (PATTERN_CREATE_INDEX.matcher(s).find()) {
            for (String it : s.replaceAll("(?i)^(create(\\s+\\S+)?\\s+index\\s+\\S+)\\s*", "$1\r\n\t").split("\r\n")) {
                if (builder.length() == 0) {
                    builder.append(it).append(linesep);
                } else if (completed) {
                    if (it.matches("^\\s*[^(]+(?:[^(),\\s]+|\\S\\([^)]+\\)[^),]*),\\s*$")) {
                        builder.append(it).append(linesep);
                    } else {
                        builder.append(it);
                        completed = false;
                    }
                } else {
                    builder.append(it.trim());
                    if (it.matches("[^)]+\\).*$")) {
                        builder.append(linesep);
                        completed = true;
                    }
                }
            }
            String tmp = builder.toString();
            builder.setLength(0);
            builder.append(tmp.replaceAll("(?i)(asc|desc)\\s*(on)", "$2"));
        } else if (PATTERN_ALTER_TABLE.matcher(s).find()) {
            for (String it : s.replaceAll("(?i)^(alter\\s+table\\s+\\S+)\\s*", "$1\r\n\t")
                    .replaceAll("(?i)\\)\\s*(references)", ")\r\n\t$1").split("\r\n")) {
                if (builder.length() == 0) {
                    builder.append(it).append(linesep);
                } else if (completed) {
                    if (it.matches("^\\s*[^(]+(?:[^(),\\s]+|\\S\\([^)]+\\)[^),]*),\\s*$")) {
                        builder.append(it).append(linesep);
                    } else {
                        builder.append(it);
                        completed = false;
                    }
                } else {
                    builder.append(it.trim());
                    if (it.matches("[^)]+\\).*$")) {
                        builder.append(linesep);
                        completed = true;
                    }
                }
            }
        } else {
            builder.append(s.trim()).append(linesep);
        }
        return builder.toString().trim();
    }

    public enum Vendor {
        eclipselink,
        hibernate,
    }

    @SuppressWarnings("unused")
    public enum ValidationMode {
        AUTO,
        CALLBACK,
        NONE
    }
}
