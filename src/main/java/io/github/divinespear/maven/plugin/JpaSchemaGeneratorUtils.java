package io.github.divinespear.maven.plugin;

import org.apache.commons.lang.NullArgumentException;
import org.codehaus.plexus.util.StringUtils;
import org.hibernate.dialect.Dialect;
import org.hibernate.engine.jdbc.dialect.internal.StandardDialectResolver;
import org.hibernate.engine.jdbc.dialect.spi.DialectResolutionInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

final class JpaSchemaGeneratorUtils {

    static final String SCHEMA_GENERATION_DATABASE_ACTION = "javax.persistence.schema-generation.database.action";
    static final String SCHEMA_GENERATION_SCRIPTS_ACTION = "javax.persistence.schema-generation.scripts.action";
    static final String SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET = "javax.persistence.schema-generation.scripts.create-target";
    static final String SCHEMA_GENERATION_SCRIPTS_DROP_TARGET = "javax.persistence.schema-generation.scripts.drop-target";
    static final String VALIDATION_MODE = "javax.persistence.validation.mode";
    static final String SCHEMA_DATABASE_PRODUCT_NAME = "javax.persistence.database-product-name";
    static final String SCHEMA_DATABASE_MAJOR_VERSION = "javax.persistence.database-major-version";
    static final String SCHEMA_DATABASE_MINOR_VERSION = "javax.persistence.database-minor-version";
    static final String JDBC_DRIVER = "javax.persistence.jdbc.driver";
    static final String JDBC_URL = "javax.persistence.jdbc.url";
    static final String JDBC_USER = "javax.persistence.jdbc.user";
    static final String JDBC_PASSWORD = "javax.persistence.jdbc.password";
    static final String SCHEMA_GENERATION_CREATE_SOURCE = "javax.persistence.schema-generation.create-source";
    static final String SCHEMA_GENERATION_METADATA_SOURCE = "metadata";
    static final String SCHEMA_GENERATION_CREATE_SCRIPT_SOURCE = "javax.persistence.schema-generation.create-script-source";
    static final String SCHEMA_GENERATION_DROP_SOURCE = "javax.persistence.schema-generation.drop-source";
    static final String SCHEMA_GENERATION_METADATA_SOURCE1 = "metadata";
    static final String SCHEMA_GENERATION_DROP_SCRIPT_SOURCE = "javax.persistence.schema-generation.drop-script-source";
    static final String ECLIPSELINK_PERSISTENCE_XML = "eclipselink.persistencexml";
    static final String WEAVING = "eclipselink.weaving";
    static final String AUTODETECTION = "hibernate.archive.autodetection";
    static final String DIALECT = "hibernate.dialect";
    static final String SCHEMA_GEN_CONNECTION = "javax.persistence.schema-generation-connection";
    static final String TRANSACTION_TYPE = "javax.persistence.transactionType";
    static final String JTA_DATASOURCE = "javax.persistence.jtaDataSource";
    static final String NON_JTA_DATASOURCE = "javax.persistence.nonJtaDataSource";
    static final String ECLIPSELINK_PERSISTENCE_XML_DEFAULT = "META-INF/persistence.xml";
    static final String SCHEMA_GENERATION_NONE_ACTION = "none";

    private JpaSchemaGeneratorUtils() {
    }

    private static boolean isDatabaseTarget(JpaSchemaGeneratorMojo mojo) {
        return !SCHEMA_GENERATION_NONE_ACTION.equalsIgnoreCase(mojo.getDatabaseAction());
    }

    private static boolean isScriptTarget(JpaSchemaGeneratorMojo mojo) {
        return !SCHEMA_GENERATION_NONE_ACTION.equalsIgnoreCase(mojo.getScriptAction());
    }

    @SuppressWarnings("deprecation")
    public static Map<String, Object> buildProperties(JpaSchemaGeneratorMojo mojo) {
        Map<String, Object> map = new HashMap<>();
        Map<String, String> properties = mojo.getProperties();

        /*
         * Common JPA options
         */
        // mode
        map.put(SCHEMA_GENERATION_DATABASE_ACTION, mojo.getDatabaseAction().toLowerCase());
        map.put(SCHEMA_GENERATION_SCRIPTS_ACTION, mojo.getScriptAction().toLowerCase());
        // output files
        if (isScriptTarget(mojo)) {
            if (mojo.getOutputDirectory() == null) {
                throw new NullArgumentException("outputDirectory is required for script generation.");
            }
            map.put(SCHEMA_GENERATION_SCRIPTS_CREATE_TARGET,
                    mojo.getCreateOutputFile().toURI().toString());
            map.put(SCHEMA_GENERATION_SCRIPTS_DROP_TARGET,
                    mojo.getDropOutputFile().toURI().toString());

        }
        // validation mode
        map.put(VALIDATION_MODE,
                mojo.getValidationMode() == null ? null : mojo.getValidationMode().toString());
        // database emulation options
        map.put(SCHEMA_DATABASE_PRODUCT_NAME, mojo.getDatabaseProductName());
        map.put(SCHEMA_DATABASE_MAJOR_VERSION,
                mojo.getDatabaseMajorVersion() == null ? null : String.valueOf(mojo.getDatabaseMajorVersion()));
        map.put(SCHEMA_DATABASE_MINOR_VERSION,
                mojo.getDatabaseMinorVersion() == null ? null : String.valueOf(mojo.getDatabaseMinorVersion()));
        // database options
        map.put(JDBC_DRIVER, mojo.getJdbcDriver());
        map.put(JDBC_URL, mojo.getJdbcUrl());
        map.put(JDBC_USER, mojo.getJdbcUser());
        map.put(JDBC_PASSWORD, mojo.getJdbcPassword());
        // source selection
        map.put(SCHEMA_GENERATION_CREATE_SOURCE, mojo.getCreateSourceMode());
        if (mojo.getCreateSourceFile() == null) {
            if (!SCHEMA_GENERATION_METADATA_SOURCE.equals(mojo.getCreateSourceMode())) {
                throw new IllegalArgumentException("create source file is required for mode "
                        + mojo.getCreateSourceMode());
            }
        } else {
            map.put(SCHEMA_GENERATION_CREATE_SCRIPT_SOURCE,
                    mojo.getCreateSourceFile().toURI().toString());
        }
        map.put(SCHEMA_GENERATION_DROP_SOURCE, mojo.getDropSourceMode());
        if (mojo.getDropSourceFile() == null) {
            if (!SCHEMA_GENERATION_METADATA_SOURCE1.equals(mojo.getDropSourceMode())) {
                throw new IllegalArgumentException("drop source file is required for mode " + mojo.getDropSourceMode());
            }
        } else {
            map.put(SCHEMA_GENERATION_DROP_SCRIPT_SOURCE,
                    mojo.getDropSourceFile().toURI().toString());
        }

        /*
         * EclipseLink specific
         */
        // persistence.xml
        map.put(ECLIPSELINK_PERSISTENCE_XML, mojo.getPersistenceXml());
        // disable weaving
        map.put(WEAVING, "false");

        /*
         * Hibernate specific
         */
        // auto-detect
        map.put(AUTODETECTION, "class,hbm");
        // dialect (without jdbc connection)
        String dialect = properties.get(DIALECT);
        if (StringUtils.isEmpty(dialect) && StringUtils.isEmpty(mojo.getJdbcUrl())) {
            final String productName = mojo.getDatabaseProductName();
            if (productName != null) {
                final int minorVersion = mojo.getDatabaseMinorVersion() == null ? 0 : mojo.getDatabaseMinorVersion();
                final int majorVersion = mojo.getDatabaseMajorVersion() == null ? 0 : mojo.getDatabaseMajorVersion();
                DialectResolutionInfo info = new DialectResolutionInfo() {
                    @Override
                    public String getDriverName() {
                        return null;
                    }

                    @Override
                    public int getDriverMinorVersion() {
                        return 0;
                    }

                    @Override
                    public int getDriverMajorVersion() {
                        return 0;
                    }

                    @Override
                    public String getDatabaseName() {
                        return productName;
                    }

                    @Override
                    public int getDatabaseMinorVersion() {
                        return minorVersion;
                    }

                    @Override
                    public int getDatabaseMajorVersion() {
                        return majorVersion;
                    }
                };
                Dialect detectedDialect = StandardDialectResolver.INSTANCE.resolveDialect(info);
                dialect = detectedDialect == null ? null : detectedDialect.getClass().getName();
            }
        }
        if (dialect != null) {
            properties.remove(DIALECT);
            map.put(DIALECT, dialect);
        }

        if (!isDatabaseTarget(mojo) && StringUtils.isEmpty(mojo.getJdbcUrl())) {
            map.put(SCHEMA_GEN_CONNECTION,
                    new ConnectionMock(mojo.getDatabaseProductName(),
                            mojo.getDatabaseMajorVersion(),
                            mojo.getDatabaseMinorVersion()));
        }

        map.putAll(mojo.getProperties());

        /* force override JTA to RESOURCE_LOCAL */
        map.put(TRANSACTION_TYPE, "RESOURCE_LOCAL");

        // normalize - remove null
        List<String> keys = new ArrayList<>(map.keySet());
        for (String key : keys) {
            if (map.get(key) == null) {
                map.remove(key);
            }
        }

        // to override datasource parameters
        map.put(JTA_DATASOURCE, null);
        map.put(NON_JTA_DATASOURCE, null);

        return map;
    }
}
