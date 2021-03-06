package io.jenkins.tools.warpackager.lib.impl;

import io.jenkins.tools.warpackager.lib.config.CasCConfig;
import io.jenkins.tools.warpackager.lib.config.Config;
import io.jenkins.tools.warpackager.lib.config.DependencyInfo;
import io.jenkins.tools.warpackager.lib.config.SourceInfo;
import io.jenkins.tools.warpackager.lib.config.WARResourceInfo;
import io.jenkins.tools.warpackager.lib.model.bom.BOM;
import io.jenkins.tools.warpackager.lib.util.SimpleManifest;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.FileUtils;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.jenkins.tools.warpackager.lib.util.SystemCommandHelper.*;

/**
 * Builds WAR according to the specified config.
 * @author Oleg Nenashev
 * @since TODO
 */
public class Builder extends PackagerBase {

    private static final Logger LOGGER = Logger.getLogger(Builder.class.getName());

    private final File buildRoot;

    // Context
    private Map<String, String> versionOverrides = new HashMap<>();

    private BOM bom = null;


    public Builder(Config config) {
        super(config);
        this.buildRoot = new File(config.buildSettings.getTmpDir(), "build");
    }

    /**
     * Performs spot-check of the input configuration.
     * It does not guarantee that the configuration is fully correct.
     */
    public void verifyConfig() throws IOException {
        if (config.casc != null && !config.casc.isEmpty()) {
            DependencyInfo dep = config.findPlugin(CasCConfig.CASC_PLUGIN_ARTIFACT_ID);
            if (dep == null) {
                throw new IOException("CasC section is declared, but CasC plugin is not declared in the plugins list");
            }
        }
    }

    public void build() throws IOException, InterruptedException {

        verifyConfig();

        // Cleanup the temporary directory
        final File tmpDir = config.buildSettings.getTmpDir();

        if (tmpDir.exists()) {
            LOGGER.log(Level.INFO, "Cleaning up the temporary directory {0}", tmpDir);
            FileUtils.deleteDirectory(tmpDir);
        }
        Files.createDirectories(buildRoot.toPath());

        // Load BOM if needed
        final File pathToBom = config.buildSettings.getBOM();
        if (pathToBom != null) {
            bom = BOM.load(pathToBom);
            LOGGER.log(Level.INFO, "Overriding settings by BOM file: {0}", pathToBom);
            config.overrideByBOM(bom, config.buildSettings.getEnvironmentName());
        }
        final File pathToPom = config.buildSettings.getPOM();
        if (pathToPom != null) {
            File downloadDir = new File(tmpDir, "hpiDownloads");
            Files.createDirectory(downloadDir.toPath());
            config.overrideByPOM(downloadDir, pathToPom);
        }

        // Verify settings
        if (config.bundle == null) {
            throw new IOException("Bundle Information must be defined by configuration file or BOM");
        }

        // Build core and plugins
        buildIfNeeded(config.war, "war");
        if (config.plugins != null) {
            for (DependencyInfo plugin : config.plugins) {
                buildIfNeeded(plugin, "hpi");
            }
        }

        // Prepare library patches
        if (config.libPatches != null) {
            for(DependencyInfo library : config.libPatches) {
                buildIfNeeded(library, "jar");
            }
        }

        // Prepare Resources
        Map<String, File> warResources = new HashMap<>();
        for (WARResourceInfo extraWarResource : config.getAllExtraResources()) {
            warResources.put(extraWarResource.id,
                    checkoutIfNeeded(extraWarResource.id, extraWarResource.source));
        }

        // Generate POM
        File warBuildDir = new File(tmpDir, "prebuild");
        Files.createDirectories(warBuildDir.toPath());
        MavenHPICustomWARPOMGenerator gen = new MavenHPICustomWARPOMGenerator(config, "-prebuild");
        Model model = gen.generatePOM(versionOverrides);
        gen.writePOM(model, warBuildDir);

        // Build WAR using Maven HPI plugin
        mavenHelper.run(warBuildDir, "clean", "package");

        // Add System properties
        File srcWar = new File(warBuildDir, "target/" + config.bundle.artifactId + "-prebuild.war");
        File explodedWar = new File(warBuildDir, "exploded-war");

        // Patch WAR
        new JenkinsWarPatcher(config, srcWar, explodedWar)
                .removeMetaInf()
                .addSystemProperties(config.systemProperties)
                .replaceLibs(versionOverrides)
                .excludeLibs()
                .addResources(warResources);

        File warOutputDir = config.buildSettings.getOutputDir();
        SimpleManifest manifest = SimpleManifest.parseFile(srcWar);
        MavenWARPackagePOMGenerator finalWar = new MavenWARPackagePOMGenerator(config, explodedWar);
        finalWar.writePOM(finalWar.generatePOM(manifest.getMain()), warOutputDir);
        mavenHelper.run(warOutputDir, "clean", config.buildSettings.isInstallArtifacts() ? "install" : "package");

        // Produce BOM
        // TODO: append status to the original BOM?
        BOM bom = new BOMBuilder(config)
                .withPluginsDir(new File(explodedWar, "WEB-INF/plugins"))
                .withStatus(versionOverrides)
                .build();
        bom.write(config.getOutputBOM());
        // TODO: also install WAR if config.buildSettings.isInstallArtifacts() is set

        if (config.buildSettings.getDocker() != null) {
            LOGGER.log(Level.INFO, "Building Dockerfile");
            new DockerfileBuilder(config)
                    .withPlugins(new File(explodedWar, "WEB-INF/plugins"))
                    .withInitScripts(new File(explodedWar, "WEB-INF"))
                    .build();
        }

        // TODO: Support custom output destinations
        // File dstWar = new File(warBuildDir, "target/" + config.bundle.artifactId + ".war");
    }

    //TODO: Merge with buildIfNeeded
    private File checkoutIfNeeded(@Nonnull String id, @Nonnull SourceInfo source) throws IOException, InterruptedException {
        File componentBuildDir = new File(buildRoot, id);
        Files.createDirectories(componentBuildDir.toPath());

        switch (source.getType()) {
            case FILESYSTEM:
                assert source.dir != null;
                LOGGER.log(Level.INFO, "Will checkout {0} from local directory: {1}", new Object[] {id, source.dir});
                return new File(source.dir);
            case GIT:
                LOGGER.log(Level.INFO, "Will checkout {0} from git: {1}", new Object[] {id, source});
                break;
            default:
                throw new IOException("Unsupported checkout source: " + source.getType());
        }

        // Git checkout and build
        processFor(componentBuildDir, "git", "clone", source.git, ".");
        String checkoutId = source.getCheckoutId();
        if (checkoutId != null) {
            processFor(componentBuildDir, "git", "checkout", checkoutId);
        }
        String commit = readFor(componentBuildDir, "git", "log", "--format=%H", "-n", "1");
        LOGGER.log(Level.INFO, "Checked out {0}, commitId: {1}", new Object[] {id, commit});
        return componentBuildDir;
    }

    private void buildIfNeeded(@Nonnull DependencyInfo dep, @Nonnull String packaging) throws IOException, InterruptedException {
        //TODO: add Caching support if commit is defined
        if (!dep.isNeedsBuild()) {
            LOGGER.log(Level.INFO, "Component {0}: no build required", dep);
            return;
        }

        File componentBuildDir = new File(buildRoot, dep.artifactId);
        Files.createDirectories(componentBuildDir.toPath());

        if (dep.source == null) {
            throw new IOException("Source is not defined for dependency " + dep);
        }

        final String newVersion;
        switch (dep.source.getType()) {
            case GIT:
                LOGGER.log(Level.INFO, "Will checkout {0} from git: {1}", new Object[] {dep.artifactId, dep.source});

                String gitRemote = dep.source.git;
                if (gitRemote == null) {
                    throw new IllegalStateException("Building dependency " + dep + "in Git mode, but Git source is not set" );
                }

                String commit = dep.source.commit;
                final String checkoutId = dep.source.getCheckoutId();
                if (commit == null) { // we use ls-remote to fetch the commit ID
                    String res = readFor(componentBuildDir, "git", "ls-remote", gitRemote, checkoutId != null ? checkoutId : "master");
                    commit = res.split("\\s+")[0];
                }

                //TODO if caching is disabled, a nice-looking version can be retrieved
                // We cannot retrieve actual base version here without checkout. 256.0 prevents dependency check failures
                newVersion = String.format("256.0-%s-%s-SNAPSHOT", checkoutId != null ? checkoutId : "default", commit);
                versionOverrides.put(dep.artifactId, newVersion);

                // TODO: add no-cache option?
                if (mavenHelper.artifactExists(componentBuildDir, dep, newVersion, packaging)) {
                    LOGGER.log(Level.INFO, "Snapshot version exists for {0}: {1}. Skipping the build",
                            new Object[] {dep, newVersion});
                    return;
                } else {
                    LOGGER.log(Level.INFO, "Snapshot is missing for {0}: {1}. Will run the build",
                            new Object[] {dep, newVersion});
                }

                processFor(componentBuildDir, "git", "clone", gitRemote, ".");
                processFor(componentBuildDir, "git", "checkout", commit);
                break;
            case FILESYSTEM:
                assert dep.source.dir != null;
                LOGGER.log(Level.INFO, "Will checkout {0} from local directory: {1}",
                        new Object[] {dep.artifactId, dep.source.dir});
                File sourceDir = new File(dep.source.dir);
                org.apache.commons.io.FileUtils.copyDirectory(sourceDir, componentBuildDir);
                newVersion = String.format("256.0-%s-SNAPSHOT", new SimpleDateFormat("yyyy-MM-dd").format(new Date()));
                versionOverrides.put(dep.artifactId, newVersion);
                break;
            default:
                throw new IOException("Unsupported checkout source: " + dep.source.getType());
        }

        // Install artifact with default version if required
        if (dep.getBuildSettings().buildOriginalVersion) {
            mavenHelper.run(componentBuildDir, "clean", "install", "-DskipTests", "-Dfindbugs.skip=true", "-Denforcer.skip=true");
        }

        // Build artifact with a custom version
        LOGGER.log(Level.INFO, "Set new version for {0}: {1}", new Object[] {dep.artifactId, newVersion});
        mavenHelper.run(componentBuildDir, "versions:set", "-DnewVersion=" + newVersion);
        mavenHelper.run(componentBuildDir, "clean", "install", "-DskipTests", "-Dfindbugs.skip=true", "-Denforcer.skip=true");
    }
}
