#!groovy

@Library('cib-pipeline-library') _

import de.cib.pipeline.library.Constants


standardMavenPipeline(
    uiParamPresets: [
        'UNIT_TESTS': true,
        'SAST': true
    ],
    primaryBranch: 'main',
    mvnParams: '-U',
    notificationUrl: Constants.NOTIFICATION_URL_CIBSEVEN,
    mvnContainerName: Constants.MAVEN_JDK_17_CONTAINER
)
