Jenkins Custom WAR Packager
===

:exclamation: This tool is under development

A small packaging tool, which bundles custom Jenkins WAR files using the YAML specification.
Generally the tool is a wrapper on the top of Maven HPI's plugin 
[Custom WAR Mojo](https://jenkinsci.github.io/maven-hpi-plugin/custom-war-mojo.html).

Differences:

* It can build package plugins from custom branches with unreleased/unstaged packages
* It can run as a CLI tool outside Maven
* It takes YAML specification instead of Maven `pom.xml`
* It allows patching WAR contents like bundled libraries, system properties
* It allows self-configuration via [Groovy Hook Scripts](https://wiki.jenkins.io/display/JENKINS/Groovy+Hook+Script)
or [Configuration-as-Code Plugin](https://github.com/jenkinsci/configuration-as-code-plugin) YAML files
* It can prepare Dockerfiles and build Docker images

### Demo

* [Jenkins WAR - all latest](./demo/all-latest-core) - bundles master branches for core and some key libraries/modules
* [Jenkins WAR - all latest with Maven](./demo/all-latest-core-maven) - same as a above, but with Maven
* [External Task Logging to Elasticsearch](./demo/external-logging-elasticsearch) -
runs External Logging demo and preconfigures it using System Groovy Hooks.
The demo is packaged with Docker, and it provides a ready-to-fly Docker Compose package.
* [Configuration as Code](./demo/casc) - configuring WAR with 
[Configuration-as-Code Plugin](https://github.com/jenkinsci/configuration-as-code-plugin) via YAML
* [Custom WAR Packager CI Demo](https://github.com/oleg-nenashev/jenkins-custom-war-packager-ci-demo) - Standalone demo with an integrated CI flow

### Usage

The tool offers a CLI interface and a Maven Plugin wrapper.

### CLI


```shell
java -jar custom-war-packager-cli.jar -configPath=mywar.yml -version=1.0-SNAPSHOT -tmpDir=tmp
```

After the build the generated WAR file will be put to `tmp/output/target/${artifactId}.war`.

To run the tool in a demo mode with [this config](./custom-war-packager-cli/src/main/resources/io/jenkins/tools/warpackager/cli/config/sample.yml), just use the following command:

```shell
java -jar war-packager-cli.jar -demo
```

Invoke the tool without options without options to get a full CLI options list.

### Maven

Maven plugin runs the packager and generates the artifact.
The artifact will be put to "target/custom-war-packager-maven-plugin/output/target/${bundle.artifactId}.war"
and added to the project artifacts.

```xml
  <build>
    <plugins>
      <plugin>
        <groupId>io.jenkins.tools.custom-war-packager</groupId>
        <artifactId>custom-war-packager-maven-plugin</artifactId>
        <version>@project.version@</version>
        <executions>
          <execution>
            <phase>package</phase>
            <goals>
              <goal>custom-war</goal>
            </goals>
            <configuration>
              <configFilePath>spotcheck.yml</configFilePath>
              <warVersion>1.1-SNAPSHOT</warVersion>
            </configuration>
          </execution>
        </executions>
      </plugin>
    </plugins>
  </build>

```

Note that this plugin invokes Maven-in-Maven, 
and that it won't pass build options to the plugin.
Configuration file can be used to configure the downstream builder.

#### Prerequisites

* Maven 3.5.0 or above
* Java 8
* Git (if any Git sources are defined)

Custom WAR Packager offers a [Docker Image](./packaging/docker-builder/README.md) which bundles all the required tools.

#### Configuration file

Example:

```yaml
bundle:
  groupId: "io.github.oleg-nenashev"
  artifactId: "mywar"
  description: "Just a WAR auto-generation-sample"
  vendor: "Jenkins project"
buildSettings:
  docker:
    base: "jenkins/jenkins:2.121.1"
    tag: "jenkins/demo-external-task-logging-elk"
    build: true
war:
  groupId: "org.jenkins-ci.main"
  artifactId: "jenkins-war"
  source:
    version: 2.107
plugins:
  - groupId: "org.jenkins-ci.plugins"
    artifactId: "matrix-project"
    source:
      version: 1.9
  - groupId: "org.jenkins-ci.plugins"
    artifactId: "durable-task"
    source:
      git: https://github.com/jglick/durable-task-plugin.git
      branch: watch-JENKINS-38381
  - groupId: "org.jenkins-ci.plugins.workflow"
    artifactId: "workflow-durable-task-step"
    source:
      git: https://github.com/jglick/workflow-durable-task-step-plugin.git
      commit: 6c424e059bba90fc94a9c1e87dc9c4a324bfef26
  - groupId: "io.jenkins"
    artifactId: "configuration-as-code"
    source:
      version: 0.11-alpha-rc373.933033f6b51e
libPatches:
  - groupId: "org.jenkins-ci.main"
    artifactId: "remoting"
    source:
      git: https://github.com/jenkinsci/remoting.git
systemProperties: {
     jenkins.model.Jenkins.slaveAgentPort: "50000",
     jenkins.model.Jenkins.slaveAgentPortEnforce: "true"}
groovyHooks:
  - type: "init"
    id: "initScripts"
    source: 
      dir: scripts
casc:
  - id: "jcasc-config"
    source:
      dir: jenkins.yml
```

There are more options available.
See the linked demos for examples.

#### BOM support

The plugin supports Bill of Materials (BOM) as an input.
This format is described in [JEP-309](https://github.com/jenkinsci/jep/tree/master/jep/309).

If BOM is defined, Custom WAR Packager will load plugin and component dependencies
from there.

```yaml
bundle:
  groupId: "io.jenkins.tools.war-packager.demo"
  artifactId: "pom-input-demo"
buildSettings:
  bom: bom.yml
  environment: aws
war:
  groupId: "org.jenkins-ci.main"
  artifactId: "jenkins-war"
  source:
    version: 2.121.1
```

An example of such configuration is available
[here](https://github.com/jenkinsci/artifact-manager-s3-plugin/pull/20).

#### Plugins from POM

In order to simplify packaging for development versions,
it is possible to link Custom War Packager to the POM file
so that it takes plugins to be bundled from there:

```yaml
bundle:
  groupId: "io.jenkins.tools.war-packager.demo"
  artifactId: "pom-input-demo"
buildSettings:
  pom: pom.xml
war:
  groupId: "org.jenkins-ci.main"
  artifactId: "jenkins-war"
  source:
    version: 2.121.1
```

If such option is set, all dependencies will be added, including test ones.
Example is available [here](./demo/artifact-manager-s3-pom).

### Limitations

Currently the tool is in the alpha state.
It has some serious limitations:

* All built artifacts with Git source are being installed to the local repository
  * Versions are unique for every commit, so beware of local repo pollution
* System properties work only for a custom `jenkins.util.SystemProperties` class defined in the core
  * Use Groovy Hook Scripts if you need to setup other system properties
* `libPatches` steps bundles only a specified JAR file, but not its dependencies.
Dependencies need to be explicitly packaged as well if they change compared to the base WAR file.
  * `libExcludes` can be used to remove dependencies which are not required anymore
