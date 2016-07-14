package io.github.divinespear.maven.plugin;

import javax.persistence.Persistence;
import javax.persistence.spi.PersistenceProvider;
import javax.persistence.spi.PersistenceUnitInfo;
import java.net.URL;
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

final class JpaSchemaGeneratorWrapper {
    private String persistenceUnitName;
    private Map properties;
    private PersistenceUnitInfo pui;

    JpaSchemaGeneratorWrapper(String persistenceUnitName, Map properties) {
        if (persistenceUnitName == null)
            throw new NullPointerException("persistenceUnitName");

        this.persistenceUnitName = persistenceUnitName;
        this.properties = properties;
    }

    JpaSchemaGeneratorWrapper(String persistenceProviderClassName, URL persistenceUnitRootUrl, List<String> managedClassNames,
                              Map properties) {
        if (persistenceProviderClassName == null)
            throw new NullPointerException("persistenceProviderClassName");
        if (persistenceUnitRootUrl == null)
            throw new NullPointerException("persistenceUnitRootUrl");
        if (managedClassNames == null)
            throw new NullPointerException("managedClassNames");

        pui = new PersistenceUnitInfoImp(persistenceProviderClassName, persistenceUnitRootUrl, managedClassNames, properties);
    }

    void generateSchema() throws Exception {
        if (persistenceUnitName != null)
            Persistence.generateSchema(persistenceUnitName, properties);
        else {
            PersistenceProvider provider = (PersistenceProvider) Class.forName(pui.getPersistenceProviderClassName()).newInstance();
            provider.generateSchema(pui, pui.getProperties());
        }
    }
}
