/*******************************************************************************
 * Copyright (c) 2008, 2013 Sonatype Inc. and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *    Sonatype Inc. - initial API and implementation
 *    SAP AG - apply DRY principle
 *******************************************************************************/
package org.eclipse.tycho.p2.impl.resolver;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.equinox.p2.core.ProvisionException;
import org.eclipse.tycho.core.facade.MavenContext;
import org.eclipse.tycho.core.facade.MavenContextImpl;
import org.eclipse.tycho.core.facade.MavenLogger;
import org.eclipse.tycho.core.facade.TargetEnvironment;
import org.eclipse.tycho.core.resolver.shared.OptionalResolutionAction;
import org.eclipse.tycho.p2.impl.publisher.DefaultDependencyMetadataGenerator;
import org.eclipse.tycho.p2.impl.publisher.DependencyMetadata;
import org.eclipse.tycho.p2.impl.publisher.P2GeneratorImpl;
import org.eclipse.tycho.p2.impl.test.ArtifactMock;
import org.eclipse.tycho.p2.metadata.IDependencyMetadata;
import org.eclipse.tycho.p2.metadata.IReactorArtifactFacade;
import org.eclipse.tycho.p2.remote.RemoteAgent;
import org.eclipse.tycho.p2.repository.LocalRepositoryP2Indices;
import org.eclipse.tycho.p2.repository.LocalRepositoryReader;
import org.eclipse.tycho.p2.resolver.facade.P2Resolver;
import org.eclipse.tycho.p2.target.PomDependencyCollectorImpl;
import org.eclipse.tycho.p2.target.TargetDefinitionResolverService;
import org.eclipse.tycho.p2.target.TargetPlatformFactoryImpl;
import org.eclipse.tycho.p2.target.facade.TargetPlatformConfigurationStub;
import org.eclipse.tycho.repository.local.LocalArtifactRepository;
import org.eclipse.tycho.repository.local.LocalMetadataRepository;
import org.eclipse.tycho.repository.local.index.LocalRepositoryP2IndicesImpl;
import org.eclipse.tycho.test.util.BuildPropertiesParserForTesting;
import org.eclipse.tycho.test.util.LogVerifier;
import org.eclipse.tycho.test.util.NoopFileLockService;
import org.junit.Before;
import org.junit.Rule;

public class P2ResolverTestBase {

    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";
    private static final String DEFAULT_GROUP_ID = "test.groupId";

    @Rule
    public final LogVerifier logVerifier = new LogVerifier();

    private P2GeneratorImpl fullGenerator;
    private DefaultDependencyMetadataGenerator dependencyGenerator;

    protected P2Resolver impl;
    protected TargetPlatformConfigurationStub tpConfig;
    protected PomDependencyCollectorImpl pomDependencies;
    protected List<IReactorArtifactFacade> reactorArtifacts = new ArrayList<IReactorArtifactFacade>();
    protected TargetPlatformFactoryImpl tpFactory;

    @Before
    final public void prepare() throws Exception {
        fullGenerator = new P2GeneratorImpl(true);
        BuildPropertiesParserForTesting buildPropertiesReader = new BuildPropertiesParserForTesting();
        fullGenerator.setBuildPropertiesParser(buildPropertiesReader);
        dependencyGenerator = new DefaultDependencyMetadataGenerator();
        dependencyGenerator.setBuildPropertiesParser(buildPropertiesReader);

        tpConfig = new TargetPlatformConfigurationStub();
        tpFactory = createTargetPlatformFactory();
    }

    protected static List<TargetEnvironment> getEnvironments() {
        List<TargetEnvironment> environments = new ArrayList<TargetEnvironment>();
        environments.add(new TargetEnvironment("linux", "gtk", "x86_64"));
        return environments;
    }

    protected final void addContextProject(File projectRoot, String packaging) throws IOException {
        ArtifactMock artifact = new ArtifactMock(projectRoot.getAbsoluteFile(), DEFAULT_GROUP_ID,
                projectRoot.getName(), DEFAULT_VERSION, packaging);

        DependencyMetadata metadata = fullGenerator.generateMetadata(artifact, getEnvironments());

        pomDependencies.addMavenArtifact(new ClassifiedLocation(artifact), artifact, metadata.getInstallableUnits());
    }

    protected final void addReactorProject(File projectRoot, String packagingType, String artifactId) {
        ArtifactMock artifact = new ArtifactMock(projectRoot, DEFAULT_GROUP_ID, artifactId, DEFAULT_VERSION,
                packagingType);
        IDependencyMetadata metadata = dependencyGenerator.generateMetadata(artifact, getEnvironments(),
                OptionalResolutionAction.REQUIRE);
        artifact.setDependencyMetadata(metadata);
        reactorArtifacts.add(artifact);
    }

    static File getLocalRepositoryLocation() throws IOException {
        return new File("target/localrepo").getAbsoluteFile();
    }

    protected final PomDependencyCollectorImpl createPomDependencyCollector() throws IOException {
        return new PomDependencyCollectorImpl(new MavenContextImpl(getLocalRepositoryLocation(),
                logVerifier.getLogger()));
    }

    protected final TargetPlatformFactoryImpl createTargetPlatformFactory() throws Exception {
        return new TestTargetPlatformBuilderFactory(logVerifier.getLogger()).createTargetPlatformFactory();
    }

    // TODO make this available as JUnit Rule
    public static class TestTargetPlatformBuilderFactory {

        private MavenContext mavenContext;
        private TargetDefinitionResolverService targetDefinitionResolverService;
        private LocalMetadataRepository localMetadataRepo;
        private LocalArtifactRepository localArtifactRepo;

        public TestTargetPlatformBuilderFactory(MavenLogger logger) throws Exception {
            boolean offline = false;
            mavenContext = createMavenContext(offline, logger);

            targetDefinitionResolverService = new TargetDefinitionResolverService(mavenContext);

            File localMavenRepoRoot = mavenContext.getLocalRepositoryRoot();
            LocalRepositoryP2Indices localRepoIndices = createLocalRepoIndices(mavenContext);
            LocalRepositoryReader localRepositoryReader = new LocalRepositoryReader(localMavenRepoRoot);
            localMetadataRepo = new LocalMetadataRepository(localMavenRepoRoot.toURI(),
                    localRepoIndices.getMetadataIndex(), localRepositoryReader);
            localArtifactRepo = new LocalArtifactRepository(localRepoIndices, localRepositoryReader);
        }

        public LocalMetadataRepository getLocalMetadataRepository() {
            return localMetadataRepo;
        }

        public TargetPlatformFactoryImpl createTargetPlatformFactory() throws ProvisionException {
            return new TargetPlatformFactoryImpl(mavenContext, new RemoteAgent(mavenContext), localArtifactRepo,
                    localMetadataRepo, targetDefinitionResolverService);
        }

        private MavenContext createMavenContext(boolean offline, MavenLogger logger) throws IOException {
            MavenContextImpl mavenContext = new MavenContextImpl(getLocalRepositoryLocation(), offline, logger,
                    new Properties());
            return mavenContext;
        }

        private LocalRepositoryP2Indices createLocalRepoIndices(MavenContext mavenContext) {
            LocalRepositoryP2IndicesImpl localRepoIndices = new LocalRepositoryP2IndicesImpl();
            localRepoIndices.setMavenContext(mavenContext);
            localRepoIndices.setFileLockService(new NoopFileLockService());
            return localRepoIndices;
        }
    }
}
