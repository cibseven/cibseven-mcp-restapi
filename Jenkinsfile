#!groovy

@Library('cib-pipeline-library') _

import de.cib.pipeline.library.Constants

// Maven Central deployment, run as the pipeline's Custom Stage (the last stage).
// On RELEASE_BUILD the library's 'Release & Deploy' stage has already released the
// version (e.g. 1.0.0), deployed it to artifacts.cibseven.org and checked out the
// release tag — so the workspace pom carries the release version. This closure then
// publishes that same tag to Maven Central via release-parent's sonatype-oss-release
// profile (sources + javadoc + GPG + central-publishing-maven-plugin);
// -Dskip.cibseven.release=true prevents a duplicate upload to artifacts.cibseven.org.
def deployToMavenCentral = {
    if (!params.RELEASE_BUILD || env.BRANCH_NAME != 'main') {
        echo 'Not a release build on main — skipping Maven Central deployment'
        return
    }
    withMaven(options: []) {
        withCredentials([
            file(credentialsId: 'credential-cibseven-community-gpg-private-key', variable: 'GPG_KEY_FILE'),
            string(credentialsId: 'credential-cibseven-community-gpg-passphrase', variable: 'GPG_KEY_PASS')
        ]) {
            sh 'gpg --batch --import ${GPG_KEY_FILE}'

            def GPG_KEYNAME = sh(script: "gpg --list-keys --with-colons | grep pub | cut -d: -f5", returnStdout: true).trim()

            sh """
                mvn -T4 -U \
                    -Dgpg.keyname="${GPG_KEYNAME}" \
                    -Dgpg.passphrase="${GPG_KEY_PASS}" \
                    clean deploy \
                    -Psonatype-oss-release,release \
                    -Dskip.cibseven.release=true
            """
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
