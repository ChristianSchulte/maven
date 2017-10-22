package org.apache.maven.plugin.internal;

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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.versioning.ComparableVersion;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.PluginResolutionException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.RequestTrace;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.DependencyCollectionContext;
import org.eclipse.aether.collection.DependencyCollectionException;
import org.eclipse.aether.collection.DependencyGraphTransformer;
import org.eclipse.aether.collection.DependencySelector;
import org.eclipse.aether.graph.DependencyFilter;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.DependencyVisitor;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactDescriptorException;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.AndDependencyFilter;
import org.eclipse.aether.util.filter.ScopeDependencyFilter;
import org.eclipse.aether.util.graph.manager.ClassicDependencyManager;
import org.eclipse.aether.util.graph.manager.DefaultDependencyManager;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;
import org.eclipse.aether.util.graph.selector.AndDependencySelector;
import org.eclipse.aether.util.graph.selector.ExclusionDependencySelector;
import org.eclipse.aether.util.graph.selector.OptionalDependencySelector;
import org.eclipse.aether.util.graph.transformer.ChainedDependencyGraphTransformer;
import org.eclipse.aether.util.repository.SimpleArtifactDescriptorPolicy;

/**
 * Assists in resolving the dependencies of a plugin. <strong>Warning:</strong> This is an internal utility class that
 * is only public for technical reasons, it is not part of the public API. In particular, this class can be changed or
 * deleted without prior notice.
 *
 * @since 3.0
 * @author Benjamin Bentmann
 */
@Component( role = PluginDependenciesResolver.class )
public class DefaultPluginDependenciesResolver
    implements PluginDependenciesResolver
{

    private static final String REPOSITORY_CONTEXT = "plugin";

    private static final String DEFAULT_PREREQUISITES = "2";

    private static final ComparableVersion DEFAULT_RESULTION_PREREQUISITES = new ComparableVersion( "3" );

    @Requirement
    private Logger logger;

    @Requirement
    private RepositorySystem repoSystem;

    private Artifact toArtifact( Plugin plugin, RepositorySystemSession session )
    {
        return new DefaultArtifact( plugin.getGroupId(), plugin.getArtifactId(), null, "jar", plugin.getVersion(),
                                    session.getArtifactTypeRegistry().get( "maven-plugin" ) );
    }

    @Override
    public Artifact resolve( Plugin plugin, List<RemoteRepository> repositories, RepositorySystemSession session )
        throws PluginResolutionException
    {
        try
        {
            final Artifact pluginArtifact = this.createPluginArtifact( plugin, session, repositories );
            final ArtifactRequest request = new ArtifactRequest( pluginArtifact, repositories, REPOSITORY_CONTEXT );
            request.setTrace( RequestTrace.newChild( null, plugin ) );
            return this.repoSystem.resolveArtifact( session, request ).getArtifact();
        }
        catch ( ArtifactDescriptorException | ArtifactResolutionException e )
        {
            throw new PluginResolutionException( plugin, e );
        }
    }

    /**
     * @since 3.3.0
     */
    public DependencyNode resolveCoreExtension( Plugin plugin, DependencyFilter dependencyFilter,
                                                List<RemoteRepository> repositories, RepositorySystemSession session )
        throws PluginResolutionException
    {
        return resolveInternal( plugin, null /* pluginArtifact */, dependencyFilter, null /* transformer */,
                                repositories, session );
    }

    public DependencyNode resolve( Plugin plugin, Artifact pluginArtifact, DependencyFilter dependencyFilter,
                                   List<RemoteRepository> repositories, RepositorySystemSession session )
        throws PluginResolutionException
    {
        return resolveInternal( plugin, pluginArtifact, dependencyFilter, new PlexusUtilsInjector(), repositories,
                                session );
    }

    private DependencyNode resolveInternal( final Plugin plugin, final Artifact artifact,
                                            final DependencyFilter dependencyFilter,
                                            final DependencyGraphTransformer transformer,
                                            final List<RemoteRepository> repositories,
                                            final RepositorySystemSession session )
        throws PluginResolutionException
    {
        // This dependency selector matches the resolver's implementation before MRESOLVER-8 got fixed. It is
        // used for plugin's with prerequisites < 3.6 to mimic incorrect but backwards compatible behaviour.
        class ClassicScopeDependencySelector implements DependencySelector
        {

            private final boolean transitive;

            ClassicScopeDependencySelector()
            {
                this( false );
            }

            private ClassicScopeDependencySelector( final boolean transitive )
            {
                super();
                this.transitive = transitive;
            }

            @Override
            public boolean selectDependency( final org.eclipse.aether.graph.Dependency dependency )
            {
                return !this.transitive
                           || !( "test".equals( dependency.getScope() )
                                 || "provided".equals( dependency.getScope() ) );

            }

            @Override
            public DependencySelector deriveChildSelector( final DependencyCollectionContext context )
            {
                ClassicScopeDependencySelector child = this;

                if ( context.getDependency() != null && !child.transitive )
                {
                    child = new ClassicScopeDependencySelector( true );
                }
                if ( context.getDependency() == null && child.transitive )
                {
                    child = new ClassicScopeDependencySelector( false );
                }

                return child;
            }

            @Override
            public boolean equals( Object obj )
            {
                boolean equal = obj instanceof ClassicScopeDependencySelector;

                if ( equal )
                {
                    final ClassicScopeDependencySelector that = (ClassicScopeDependencySelector) obj;
                    equal = this.transitive == that.transitive;
                }

                return equal;
            }

            @Override
            public int hashCode()
            {
                int hash = 17;
                hash = hash * 31 + ( ( (Boolean) this.transitive ).hashCode() );
                return hash;
            }

        }

        final RepositorySystemSession verboseSession;
        if ( this.logger.isDebugEnabled()
                 && session.getConfigProperties().get( DependencyManagerUtils.CONFIG_PROP_VERBOSE ) == null )
        {
            final DefaultRepositorySystemSession defaultSession = new DefaultRepositorySystemSession( session );
            defaultSession.setConfigProperty( DependencyManagerUtils.CONFIG_PROP_VERBOSE, Boolean.TRUE );
            verboseSession = defaultSession;
        }
        else
        {
            verboseSession = session;
        }

        final RequestTrace trace = RequestTrace.newChild( null, plugin );
        final DependencyFilter collectionFilter = new ScopeDependencyFilter( "provided", "test" );
        final DependencyFilter resolutionFilter = AndDependencyFilter.newInstance( collectionFilter, dependencyFilter );

        try
        {
            final Artifact pluginArtifact = artifact != null
                                                ? this.createPluginArtifact( artifact, verboseSession, repositories )
                                                : this.createPluginArtifact( plugin, verboseSession, repositories );

            final ComparableVersion prerequisites =
                new ComparableVersion( pluginArtifact.getProperty( "requiredMavenVersion", DEFAULT_PREREQUISITES ) );

            final boolean classicResolution = prerequisites.compareTo( DEFAULT_RESULTION_PREREQUISITES ) < 0;

            if ( this.logger.isDebugEnabled() )
            {
                if ( classicResolution )
                {
                    this.logger.debug( String.format(
                        "Constructing classic plugin classpath '%s' for prerequisites '%s'.",
                        pluginArtifact, prerequisites ) );

                }
                else
                {
                    this.logger.debug( String.format(
                        "Constructing default plugin classpath '%s' for prerequisites '%s'.",
                        pluginArtifact, prerequisites ) );

                }
            }

            final DependencySelector pluginDependencySelector =
                classicResolution
                    ? new AndDependencySelector( new ClassicScopeDependencySelector(), // incorrect - see MRESOLVER-8
                                                 new OptionalDependencySelector(),
                                                 new ExclusionDependencySelector(),
                                                 new WagonExcluder() )
                    : AndDependencySelector.newInstance( verboseSession.getDependencySelector(), new WagonExcluder() );

            final DependencyGraphTransformer pluginDependencyGraphTransformer =
                ChainedDependencyGraphTransformer.newInstance( verboseSession.getDependencyGraphTransformer(),
                                                               transformer );

            DefaultRepositorySystemSession pluginSession = new DefaultRepositorySystemSession( verboseSession );
            pluginSession.setDependencySelector( pluginDependencySelector );
            pluginSession.setDependencyGraphTransformer( pluginDependencyGraphTransformer );
            pluginSession.setDependencyManager( classicResolution
                                                    ? new ClassicDependencyManager()
                                                    : new DefaultDependencyManager() );

            CollectRequest request = new CollectRequest();
            request.setRequestContext( REPOSITORY_CONTEXT );
            request.setRepositories( repositories );

            for ( Dependency dependency : plugin.getDependencies() )
            {
                org.eclipse.aether.graph.Dependency pluginDep =
                    RepositoryUtils.toDependency( dependency, verboseSession.getArtifactTypeRegistry() );

                if ( !JavaScopes.SYSTEM.equals( pluginDep.getScope() ) )
                {
                    pluginDep = pluginDep.setScope( JavaScopes.RUNTIME );
                }

                request.addDependency( pluginDep );
                request.addManagedDependency( pluginDep );
            }

            request.setRoot( new org.eclipse.aether.graph.Dependency( pluginArtifact, null ) );

            DependencyRequest depRequest = new DependencyRequest( request, resolutionFilter );
            depRequest.setTrace( trace );

            request.setTrace( RequestTrace.newChild( trace, depRequest ) );

            final DependencyNode node = repoSystem.collectDependencies( pluginSession, request ).getRoot();

            if ( logger.isDebugEnabled() )
            {
                node.accept( new GraphLogger() );
            }

            depRequest.setRoot( node );
            repoSystem.resolveDependencies( verboseSession, depRequest );
            return node;
        }
        catch ( ArtifactDescriptorException | DependencyCollectionException e )
        {
            throw new PluginResolutionException( plugin, e );
        }
        catch ( DependencyResolutionException e )
        {
            throw new PluginResolutionException( plugin, e.getCause() );
        }
    }

    private Artifact createPluginArtifact( final Plugin plugin,
                                           final RepositorySystemSession session,
                                           final List<RemoteRepository> repositories )
        throws ArtifactDescriptorException
    {
        return this.createPluginArtifact( toArtifact( plugin, session ), session, repositories );
    }

    private Artifact createPluginArtifact( final Artifact artifact,
                                           final RepositorySystemSession session,
                                           final List<RemoteRepository> repositories )
        throws ArtifactDescriptorException
    {
        Artifact pluginArtifact = artifact;
        final DefaultRepositorySystemSession pluginSession = new DefaultRepositorySystemSession( session );
        pluginSession.setArtifactDescriptorPolicy( new SimpleArtifactDescriptorPolicy( true, false ) );

        final ArtifactDescriptorRequest request =
            new ArtifactDescriptorRequest( pluginArtifact, repositories, REPOSITORY_CONTEXT );

        request.setTrace( RequestTrace.newChild( null, artifact ) );

        final ArtifactDescriptorResult result = this.repoSystem.readArtifactDescriptor( pluginSession, request );

        pluginArtifact = result.getArtifact();

        final String requiredMavenVersion = (String) result.getProperties().get( "prerequisites.maven" );

        if ( requiredMavenVersion != null )
        {
            final Map<String, String> props = new LinkedHashMap<>( pluginArtifact.getProperties() );
            props.put( "requiredMavenVersion", requiredMavenVersion );
            pluginArtifact = pluginArtifact.setProperties( props );
        }

        return pluginArtifact;
    }

    // Keep this class in sync with org.apache.maven.project.DefaultProjectDependenciesResolver.GraphLogger
    class GraphLogger
        implements DependencyVisitor
    {

        private String indent = "";

        GraphLogger()
        {
            super();
        }

        @Override
        public boolean visitEnter( DependencyNode node )
        {
            StringBuilder buffer = new StringBuilder( 128 );
            buffer.append( indent );
            org.eclipse.aether.graph.Dependency dep = node.getDependency();
            if ( dep != null )
            {
                org.eclipse.aether.artifact.Artifact art = dep.getArtifact();

                buffer.append( art );
                buffer.append( ':' ).append( dep.getScope() );

                // TODO We currently cannot tell which <dependencyManagement> section contained the management
                //      information. When resolver 1.1 provides this information, these log messages should be updated
                //      to contain it.
                if ( ( node.getManagedBits() & DependencyNode.MANAGED_SCOPE ) == DependencyNode.MANAGED_SCOPE )
                {
                    final String premanagedScope = DependencyManagerUtils.getPremanagedScope( node );
                    buffer.append( " (scope managed from " );
                    buffer.append( StringUtils.defaultString( premanagedScope, "default" ) );
                    buffer.append( ')' );
                }

                if ( ( node.getManagedBits() & DependencyNode.MANAGED_VERSION ) == DependencyNode.MANAGED_VERSION )
                {
                    final String premanagedVersion = DependencyManagerUtils.getPremanagedVersion( node );
                    buffer.append( " (version managed from " );
                    buffer.append( StringUtils.defaultString( premanagedVersion, "default" ) );
                    buffer.append( ')' );
                }

                if ( ( node.getManagedBits() & DependencyNode.MANAGED_OPTIONAL ) == DependencyNode.MANAGED_OPTIONAL )
                {
                    final Boolean premanagedOptional = DependencyManagerUtils.getPremanagedOptional( node );
                    buffer.append( " (optionality managed from " );
                    buffer.append( StringUtils.defaultString( premanagedOptional, "default" ) );
                    buffer.append( ')' );
                }

                if ( ( node.getManagedBits() & DependencyNode.MANAGED_EXCLUSIONS )
                         == DependencyNode.MANAGED_EXCLUSIONS )
                {
                    // TODO As of resolver 1.1, use DependencyManagerUtils.getPremanagedExclusions( node ).
                    //      The resolver 1.0.x releases do not record premanaged state of exclusions.
                    buffer.append( " (exclusions managed)" );
                }

                if ( ( node.getManagedBits() & DependencyNode.MANAGED_PROPERTIES )
                         == DependencyNode.MANAGED_PROPERTIES )
                {
                    // TODO As of resolver 1.1, use DependencyManagerUtils.getPremanagedProperties( node ).
                    //      The resolver 1.0.x releases do not record premanaged state of properties.
                    buffer.append( " (properties managed)" );
                }
            }

            logger.debug( buffer.toString() );
            indent += "   ";
            return true;
        }

        @Override
        public boolean visitLeave( DependencyNode node )
        {
            indent = indent.substring( 0, indent.length() - 3 );
            return true;
        }

    }

}
