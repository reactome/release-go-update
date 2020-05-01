
import groovy.json.JsonSlurper
// This Jenkinsfile is used by Jenkins to run the GOUpdate step of Reactome's release.
// It requires that the ConfirmReleaseConfigs step has been run successfully before it can be run.
def currentRelease
def previousRelease
pipeline {
	agent any

	stages {
		// This stage checks that an upstream project, ConfirmReleaseConfig, was run successfully for its last build.
		stage('Check ConfirmReleaseConfig build succeeded'){
			steps{
				script{
					// Get current release number from directory
					currentRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1];
					previousRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1].toInteger() - 1;
					// This queries the Jenkins API to confirm that the most recent build of ConfirmReleaseConfigs was successful.
					def configStatusUrl = httpRequest authentication: 'jenkinsKey', validResponseCodes: "${env.VALID_RESPONSE_CODES}", url: "${env.JENKINS_JOB_URL}/job/${currentRelease}/job/ConfirmReleaseConfigs/lastBuild/api/json"
					if (configStatusUrl.getStatus() == 404) {
						error("ConfirmReleaseConfigs has not yet been run. Please complete a successful build.")
					} else {
						def configStatusJson = new JsonSlurper().parseText(configStatusUrl.getContent())
						if (configStatusJson['result'] != "SUCCESS"){
							error("Most recent ConfirmReleaseConfigs build status: " + configStatusJson['result'] + ". Please complete a successful build.")
						}
					}
				}
			}
		}
	}
}
