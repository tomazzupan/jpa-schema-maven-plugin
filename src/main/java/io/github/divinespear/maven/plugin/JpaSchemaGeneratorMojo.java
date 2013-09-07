package io.github.divinespear.maven.plugin;

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

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.settings.Settings;

/**
 * Generate database schema or DDL scripts.
 * 
 * @author divinespear
 */
@Mojo(name = "generate",
      defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class JpaSchemaGeneratorMojo
        extends AbstractMojo {

    private final Log log = this.getLog();

    @Component
    private MavenSession session;

    @Component
    private MavenProject project;

    @Component
    private MojoExecution mojo;

    // for Maven 3 only
    @Component
    private PluginDescriptor plugin;

    @Component
    private Settings settings;

    /**
     * skip schema generation
     */
    @Parameter(property = "jpa-schema.generate.skip", required = true, defaultValue = "false")
    private boolean skip;

    /**
     * scan test classes
     */
    @Parameter(property = "jpa-schema.generate.scan-test-classes", required = true, defaultValue = "false")
    private boolean scanTestClasses;

    /**
     * JPA version
     * <p>
     * support value is <code>2.0</code> or <code>2.1</code>.
     * <p>
     * Note for JPA 2.1, version of implementation must be:
     * <ul>
     * <li>EclipseLink: 2.5.0 or newer</li>
     * <li>Hibernate: 4.3.0 or newer</li>
     * </ul>
     */
    @Parameter(required = true, defaultValue = "2.1")
    private String jpaVersion;

    /**
     * JPA implementation
     * <p>
     * support value is <code>eclipselink</code> or <code>hibernate</code>, as case-insensitive.
     */
    @Parameter(required = true, defaultValue = "eclipselink")
    private String implementation;

    /**
     * location of <code>persistence.xml</code> file
     */
    @Parameter(required = true, defaultValue = "META-INF/persistence.xml")
    private String persistenceXml;

    public String getPersistenceXml() {
        return persistenceXml;
    }

    /**
     * unit name of <code>persistence.xml</code>
     */
    @Parameter(required = true, defaultValue = "default")
    private String persistenceUnitName;

    public String getPersistenceUnitName() {
        return persistenceUnitName;
    }

    /**
     * schema generation target
     * <p>
     * support value is <code>database</code>, <code>script</code>, or <code>both</code>.
     */
    @Parameter(required = true, defaultValue = "both")
    private String target;

    public String getTarget() {
        return target;
    }

    /**
     * schema generation mode
     * <p>
     * support value is <code>create</code>, <code>drop</code>, or <code>drop-and-create</code>.
     */
    @Parameter(required = true, defaultValue = "drop-and-create")
    private String mode;

    public String getMode() {
        return mode;
    }

    /**
     * output directory for generated ddl scripts
     */
    @Parameter(defaultValue = "${project.build.directory}/generated-schema")
    private File outputDirectory;

    public File getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * generated create script name
     */
    @Parameter(defaultValue = "create.sql")
    private String createOutputFileName;

    public String getCreateOutputFileName() {
        return createOutputFileName;
    }

    /**
     * generated drop script name
     */
    @Parameter(defaultValue = "drop.sql")
    private String dropOutputFileName;

    public String getDropOutputFileName() {
        return dropOutputFileName;
    }

    /**
     * jdbc driver class name
     * <p>
     * default is declared class name in persistence xml.
     * <p>
     * and remember, <strike><a href="http://callofduty.wikia.com/wiki/No_Russian" target="_blank">No
     * Russian</a></strike> you MUST configure database driver dependency in your <code>pom.xml</code>.
     */
    @Parameter
    private String jdbcDriver;

    public String getJdbcDriver() {
        return jdbcDriver;
    }

    /**
     * jdbc connection url
     * <p>
     * default is declared connection url in persistence xml.
     */
    @Parameter
    private String jdbcUrl;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    /**
     * jdbc connection username
     * <p>
     * default is declared username in persistence xml.
     */
    @Parameter
    private String jdbcUser;

    public String getJdbcUser() {
        return jdbcUser;
    }

    /**
     * jdbc connection password
     * <p>
     * default is declared password in persistence xml.
     * <p>
     * Note for local file-based database (like Derby, H2, etc...): if you config database connection with
     * <code>&lt;configuration&gt;</code>, then MUST use <code>&lt;databasePassword&gt;&lt;/databasePassword&gt;</code>
     * for empty password.
     */
    @Parameter
    private String jdbcPassword;

    public String getJdbcPassword() {
        return jdbcPassword;
    }

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

    public String getDatabaseProductName() {
        return databaseProductName;
    }

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
    private int databaseMajorVersion;

    public int getDatabaseMajorVersion() {
        return databaseMajorVersion;
    }

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
    private int databaseMinorVersion;

    public int getDatabaseMinorVersion() {
        return databaseMinorVersion;
    }

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        if (this.skip) {
            log.info("Generating schema is skipped.");
            return;
        }

        log.info("* JPA Version           : " + this.jpaVersion);
        log.info("* JPA Implementation    : " + this.implementation);
        log.info("* Persistence XML       : " + this.persistenceXml);
        log.info("* Persistence Unit Name : " + this.persistenceUnitName);
        log.info("* Generation Target     : " + this.target);
        log.info("* Generation Mode       : " + this.mode);
        log.info("* Output Directory      : " + this.outputDirectory);
        log.info("  - Create Script Name  : " + this.createOutputFileName);
        log.info("  - Drop Script Name    : " + this.dropOutputFileName);

        if (!this.outputDirectory.exists()) {
            this.outputDirectory.mkdirs();
        }

        final ClassLoader classLoader = this.getProjectClassLoader();
        final String providerId = (this.implementation.toLowerCase() + "_" + this.jpaVersion.toLowerCase()).trim();
        final SchemaGeneratorProvider provider = PROVIDER_MAP.get(providerId);
        log.info("* Selected provider     : " + providerId + "(" + provider.getClass().toString() + ")");

        PROVIDER_MAP.get(providerId).execute(classLoader, this);
    }

    private ClassLoader getProjectClassLoader() throws MojoExecutionException {
        try {
            List<String> classfiles = this.project.getCompileClasspathElements();
            if (this.scanTestClasses) {
                classfiles.addAll(this.project.getTestClasspathElements());
            }
            List<URL> classURL = new ArrayList<URL>(classfiles.size());
            log.debug("* Dependencies:");
            for (String classfile : classfiles) {
                log.debug("  - " + classfile);
                classURL.add(new File(classfile).toURI().toURL());
            }
            return new URLClassLoader(classURL.toArray(EMPTY_URLS), this.getClass().getClassLoader());
        } catch (Exception e) {
            throw new MojoExecutionException("Error while creating classloader", e);
        }
    }

    private static final Map<String, SchemaGeneratorProvider> PROVIDER_MAP;
    static {
        Map<String, SchemaGeneratorProvider> map = new HashMap<String, SchemaGeneratorProvider>();
        try {
            registerProvider(map, JPA21EclipseLinkProviderImpl.class);
            PROVIDER_MAP = Collections.unmodifiableMap(map);
        } catch (Exception e) {
            throw new RuntimeException("exception while initialize", e);
        }
    }
    private static final URL[] EMPTY_URLS = new URL[0];

    private static void registerProvider(Map<String, SchemaGeneratorProvider> map,
                                         Class<? extends SchemaGeneratorProvider> clazz) throws Exception {
        SchemaGeneratorProvider provider = clazz.newInstance();
        map.put(provider.providerName(), provider);
    }
}