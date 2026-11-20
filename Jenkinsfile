#!groovy

@Library('cib-pipeline-library') _

import de.cib.pipeline.library.Constants

// Maven Central publishing, run as the pipeline's 'Custom Stage' (its last stage).
//
// Only a release build (RELEASE_BUILD=true) publishes, and only
// - on main: 'Release & Deploy' has already released the version to artifacts.cibseven.org and
//   checked out the release tag, so the workspace holds the released version;
// - on a branch central/<version> created from an existing release tag: there the pipeline skips
//   both deployments to artifacts.cibseven.org, so the release goes to Maven Central only.
//
// release-parent's sonatype-oss-release profile adds sources, javadoc and GPG signatures and uploads
// the deployment to the Central Portal, where it is validated but not published: publishing is a
// manual step on central.sonatype.com. -Dskip.cibseven.release=true skips maven-deploy-plugin, which
// would otherwise upload the release to artifacts.cibseven.org a second time (rejected there).
// The GPG key and its passphrase reach the commands as shell variables only.
def deployToMavenCentral = {
    boolean centralBranch = env.BRANCH_NAME.startsWith('central/')
    if (!params.RELEASE_BUILD || !(env.BRANCH_NAME == 'main' || centralBranch)) {
        echo 'Maven Central: not a release build on main or on a central/* branch, skipping'
        return
    }
    String version = readMavenPom(file: 'pom.xml').version
    if (version.endsWith('-SNAPSHOT')) {
        error("Maven Central: ${version} is a SNAPSHOT version, not publishing")
    }
    if (centralBranch && env.BRANCH_NAME != "central/${version}") {
        error("Maven Central: branch ${env.BRANCH_NAME} does not match the version ${version} in pom.xml")
    }
    withMaven(options: []) {
        withCredentials([
            file(credentialsId: 'credential-cibseven-gpg-private-key', variable: 'GPG_KEY_FILE'),
            string(credentialsId: 'credential-cibseven-gpg-passphrase', variable: 'GPG_KEY_PASS')
        ]) {
            sh '''
                gpg --batch --import "$GPG_KEY_FILE"
                GPG_KEYNAME=$(gpg --list-keys --with-colons | grep '^pub' | cut -d: -f5)
                mvn -U clean deploy \
                    -Psonatype-oss-release \
                    -Dskip.cibseven.release=true \
                    -Dgpg.keyname="$GPG_KEYNAME" \
                    -Dgpg.passphrase="$GPG_KEY_PASS"
            '''
        }
    }
}

standardMavenPipeline(
    uiParamPresets: [
        'UNIT_TESTS': true,
        'SAST': true
    ],
    primaryBranch: 'main',
    mvnParams: '-U',
    customStageBody: deployToMavenCentral,
    notificationUrl: Constants.NOTIFICATION_URL_CIBSEVEN,
    mvnContainerName: Constants.MAVEN_JDK_17_CONTAINER
)
