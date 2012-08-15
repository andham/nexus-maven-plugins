/**
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.maven.staging.it;

import static org.sonatype.nexus.client.rest.BaseUrl.baseUrlFrom;
import static org.sonatype.nexus.testsuite.support.NexusStartAndStopStrategy.Strategy.*;
import static org.sonatype.nexus.testsuite.support.ParametersLoaders.firstAvailableTestParameters;
import static org.sonatype.nexus.testsuite.support.ParametersLoaders.systemTestParameters;
import static org.sonatype.nexus.testsuite.support.ParametersLoaders.testParameters;
import static org.sonatype.sisu.filetasks.builder.FileRef.file;
import static org.sonatype.sisu.goodies.common.Varargs.$;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.inject.Inject;

import org.apache.maven.it.VerificationException;
import org.apache.maven.it.Verifier;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.DefaultModelReader;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.Timeout;
import org.junit.runners.Parameterized;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.bundle.launcher.NexusBundleConfiguration;
import org.sonatype.nexus.client.core.NexusClient;
import org.sonatype.nexus.client.rest.NexusClientFactory;
import org.sonatype.nexus.client.rest.UsernamePasswordAuthenticationInfo;
import org.sonatype.nexus.mindexer.client.MavenIndexer;
import org.sonatype.nexus.mindexer.client.SearchResponse;
import org.sonatype.nexus.testsuite.support.NexusRunningParametrizedITSupport;
import org.sonatype.nexus.testsuite.support.NexusStartAndStopStrategy;
import org.sonatype.sisu.filetasks.FileTaskBuilder;
import org.sonatype.sisu.goodies.common.Time;

import com.google.common.base.Throwables;
import com.sonatype.nexus.staging.client.Profile;
import com.sonatype.nexus.staging.client.StagingRepository;
import com.sonatype.nexus.staging.client.StagingWorkflowV2Service;
import com.sonatype.nexus.testsuite.support.NexusProConfigurator;

/**
 * A base class that gets and prepares given version of Maven. Also, it creates Verifier for it.
 * 
 * @author cstamas
 */
@NexusStartAndStopStrategy( EACH_TEST )
public abstract class StagingMavenPluginITSupport
    extends NexusRunningParametrizedITSupport
{

    @Parameterized.Parameters
    public static Collection<Object[]> data()
    {
        return firstAvailableTestParameters(
            systemTestParameters(),
            testParameters(
                $( "com.sonatype.nexus:nexus-professional:zip:bundle" )
            )
        ).load();
    }

    protected final Logger logger = LoggerFactory.getLogger( getClass() );

    @Rule
    public final Timeout defaultTimeout = new Timeout( Time.minutes( 10 ).toMillisI() );

    @Inject
    private NexusClientFactory nexusClientFactory;

    @Inject
    private FileTaskBuilder fileTaskBuilder;

    private NexusClient nexusDeploymentClient;

    public StagingMavenPluginITSupport( final String nexusBundleCoordinates )
    {
        super( nexusBundleCoordinates );
    }

    @Override
    protected NexusBundleConfiguration configureNexus( final NexusBundleConfiguration configuration )
    {
        return new NexusProConfigurator( this ).configure( configuration );
    }

    private final String MAVEN_G = "org.apache.maven";

    private final String MAVEN_A = "apache-maven";

    private final Map<String, File> mavenHomes = new LinkedHashMap<String, File>();

    public static final String M2_VERSION = "2.2.1";

    public static final String M3_VERSION = "3.0.4";

    /**
     * Override this method to have other than default versions involved.
     * 
     * @return
     */
    protected List<String> getMavenVersions()
    {
        return Arrays.asList( M2_VERSION, M3_VERSION );
    }

    // {@code <groupId>:<artifactId>[:<extension>[:<classifier>]]:<version>} formatted string.
    @Before
    public void resolveMavens()
        throws IOException
    {
        logger.info( "Setting up Maven binaries..." );
        final File mavenHomesBase = new File( getBasedir(), "target/maven" );
        for ( String mavenVersion : getMavenVersions() )
        {
            final File mavenHome = new File( mavenHomesBase, MAVEN_A + "-" + mavenVersion );
            if ( !mavenHome.isDirectory() )
            {
                logger.info( "  Expanding Maven " + mavenVersion + "..." );
                final File maven2bundle = artifactResolver().resolveArtifact(
                    MAVEN_G + ":" + MAVEN_A + ":zip:bin:" + mavenVersion );
                fileTaskBuilder.expand( file( maven2bundle ) ).to().directory( file( mavenHomesBase ) ).run();
                fileTaskBuilder.chmod( file( new File( mavenHome, "bin" ) ) ).include( "mvn" ).permissions( "755" ).run();
            }
            else
            {
                logger.info( "  Reusing Maven " + mavenVersion + "..." );
            }
            mavenHomes.put( mavenVersion, mavenHome );
        }
        logger.info( "  Maven binaries set up..." );
    }

    @Before
    public void createClient()
    {
        logger.info( "Creating NexusClient..." );
        nexusDeploymentClient =
            nexusClientFactory.createFor( baseUrlFrom( nexus().getUrl() ), new UsernamePasswordAuthenticationInfo(
                "deployment", "deployment123" ) );
    }

    public MavenIndexer getMavenIndexer()
    {
        return nexusDeploymentClient.getSubsystem( MavenIndexer.class );
    }

    public StagingWorkflowV2Service getStagingWorkflowV2Service()
    {
        return nexusDeploymentClient.getSubsystem( StagingWorkflowV2Service.class );
    }

    public PreparedVerifier createMavenVerifier( final String testId, final String mavenVersion,
                                                 final File mavenSettings, final File baseDir )
        throws VerificationException, IOException
    {
        final File mavenHome = mavenHomes.get( mavenVersion );
        if ( mavenHome == null || !mavenHome.isDirectory() )
        {
            throw new IllegalArgumentException( "Maven version " + mavenVersion + " was not prepared!" );
        }

        final String logname = testId + "-maven-" + mavenVersion + ".log";
        final String localRepoName = "target/maven-local-repository/";
        final File localRepoFile = new File( getBasedir(), localRepoName );
        final File filteredSettings = new File( getBasedir(), "target/settings.xml" );
        fileTaskBuilder.copy().file( file( mavenSettings ) ).filterUsing( "nexus.port",
            String.valueOf( nexus().getPort() ) ).to().file( file( filteredSettings ) ).run();

        final String projectGroupId;
        final String projectArtifactId;
        final String projectVersion;
        // filter the POM if needed
        final File pom = new File( baseDir, "pom.xml" );
        final File rawPom = new File( baseDir, "raw-pom.xml" );
        if ( rawPom.isFile() )
        {
            projectGroupId = getClass().getPackage().getName();
            projectArtifactId = baseDir.getName() + "-" + mavenVersion;
            projectVersion = "1.0";
            final Properties context = new Properties();
            context.setProperty( "nexus.port", String.valueOf( nexus().getPort() ) );
            context.setProperty( "itproject.groupId", projectGroupId );
            context.setProperty( "itproject.artifactId", projectArtifactId );
            context.setProperty( "itproject.version", projectVersion );
            filterPomsIfNeeded( baseDir, context );
        }
        else
        {
            // TODO: improve this, as this below is not quite true,
            // but this will do it for now and we do not use non-interpolated POMs for now anyway
            final Model model = new DefaultModelReader().read( pom, null );
            projectGroupId = model.getGroupId();
            projectArtifactId = model.getArtifactId();
            projectVersion = model.getVersion();
        }

        System.setProperty( "maven.home", mavenHome.getAbsolutePath() );
        Verifier verifier = new Verifier( baseDir.getAbsolutePath(), false );
        verifier.setAutoclean( false ); // no autoclean to be able to simulate multiple invocations
        verifier.setLogFileName( logname );
        verifier.setLocalRepo( localRepoFile.getAbsolutePath() );
        verifier.resetStreams();
        List<String> options = new ArrayList<String>();
        // options.add( "-X" );
        options.add( "-Dmaven.repo.local=" + localRepoFile.getAbsolutePath() );
        options.add( "-s " + filteredSettings.getAbsolutePath() );
        verifier.setCliOptions( options );
        return new PreparedVerifier( verifier, baseDir, projectGroupId, projectArtifactId, projectVersion );
    }

    /**
     * Recurses the baseDir searching for POMs and filters them.
     * 
     * @param baseDir
     * @param properties
     * @throws IOException
     */
    protected void filterPomsIfNeeded( final File baseDir, final Properties properties )
        throws IOException
    {
        final File pom = new File( baseDir, "pom.xml" );
        final File rawPom = new File( baseDir, "raw-pom.xml" );
        if ( rawPom.isFile() )
        {
            fileTaskBuilder.copy().file( file( rawPom ) ).filterUsing( properties ).to().file( file( pom ) ).run();
        }
        else if ( !pom.isFile() )
        {
            // error
            throw new IOException( "No raw-POM nor proper POM found!" );
        }

        final File[] fileList = baseDir.listFiles();
        if ( fileList != null )
        {
            for ( File file : fileList )
            {
                // recurse only non src and target folders (sanity check)
                if ( file.isDirectory() && !"src".equals( file.getName() ) && !"target".equals( file.getName() ) )
                {
                    filterPomsIfNeeded( file, properties );
                }
            }
        }
    }

    // ==

    /**
     * Returns the list of all staging repositories - whether open or closed - found in all profiles (all staging
     * repositories present instance-wide).
     * 
     * @return
     */
    protected List<StagingRepository> getAllStagingRepositories()
    {
        final ArrayList<StagingRepository> result = new ArrayList<StagingRepository>();
        final StagingWorkflowV2Service stagingWorkflow = getStagingWorkflowV2Service();
        final List<Profile> profiles = stagingWorkflow.listProfiles();
        for ( Profile profile : profiles )
        {
            final List<StagingRepository> stagingRepositories =
                stagingWorkflow.listStagingRepositories( profile.getId() );
            result.addAll( stagingRepositories );
        }
        return result;
    }

    /**
     * Returns the list of all staging repositories - whether open or closed - found in passed in profile.
     * 
     * @return
     */
    protected List<StagingRepository> getProfileStagingRepositories( final Profile profile )
    {
        List<StagingRepository> stagingRepositories =
            getStagingWorkflowV2Service().listStagingRepositories( profile.getId() );
        return stagingRepositories;
    }

    /**
     * Performs a "cautious" search for GAV that is somewhat "shielded" against Nexus Indexer asynchronicity. It will
     * repeat the search 3 times, with 1000 milliseconds pause. The reason to do this, to be "almost sure" it is or it
     * is not found, as Maven Indexer performs commits every second (hence, search might catch the pre-commit state),
     * but also the execution path as for example a deploy "arrives" to index is itself async too
     * (AsynchronousEventInspector). Hence, this method in short does a GAV search, but is "shielded" with some retries
     * and sleeps to make sure that result is correct. For input parameters see
     * {@link MavenIndexer#searchByGAV(String, String, String, String, String, String)} method.
     * 
     * @return
     */
    protected SearchResponse searchThreeTimesForGAV( final String groupId, final String artifactId,
                                                     final String version, final String classifier, final String type,
                                                     final String repositoryId )
    {
        SearchResponse response = null;
        for ( int i = 0; i < 3; i++ )
        {
            response = getMavenIndexer().searchByGAV( groupId, artifactId, version, classifier, type, repositoryId );
            try
            {
                Thread.sleep( 1000 );
            }
            catch ( InterruptedException e )
            {
                Throwables.propagate( e );
            }
        }
        return response;
    }
}
