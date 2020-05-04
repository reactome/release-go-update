
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
					currentRelease = (pwd() =~ /(\d+)\//)[0][1];
					previousRelease = (pwd() =~ /(\d+)\//)[0][1].toInteger() - 1;
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
		/*
		// This stage backs up the gk_central database before it is modified.
		stage('Setup: Back up gk_central before modifications'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						def central_before_go_update_dump = "${env.GK_CENTRAL}_${currentRelease}_before_go_update.dump"
						sh "mysqldump -u$user -p$pass -h${env.CURATOR_SERVER} ${env.GK_CENTRAL} > $central_before_go_update_dump"
						sh "gzip -f $central_before_go_update_dump"
					}
				}
			}
		}
		// Download go.obo and ec2go files from GO.
		stage('Setup: Download go.obo and ec2go files'){
			steps{
				script{
					sh "wget http://current.geneontology.org/ontology/go.obo"
					sh "wget http://current.geneontology.org/ontology/external2go/ec2go"
					sh "mv go.obo src/main/resources/"
					sh "mv ec2go src/main/resources/"
				}
			}
		}
		// This stage builds the jar file using maven.
		stage('Setup: Build jar file'){
			steps{
				script{
					sh "mvn clean compile assembly:single"
				}
			}
		}
		// This stage executes the GOUpdate jar file. 
		stage('Main: GO Update'){
			steps {
				script{
					withCredentials([file(credentialsId: 'Config', variable: 'ConfigFile')]){
						sh "java -Xmx${env.JAVA_MEM_MAX}m -jar target/go-update-*-jar-with-dependencies.jar $ConfigFile"
					}
				}
			}
		}
		// This stage backs up the gk_central and slice_current databases after they have been modified.
		stage('Post: Backup gk_central after modifications'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						def central_after_update_go_update_dump = "${env.GK_CENTRAL}_${currentRelease}_after_go_update.dump"
						sh "mysqldump -u$user -p$pass -h${env.CURATOR_SERVER} ${env.GK_CENTRAL} > $central_after_update_go_update_dump"
						sh "gzip -f $central_after_update_go_update_dump"
					}
				}
			}
		}
		*/
		stage('Post: Email GO Update Reports'){
			steps{
				script{
					sh "tar zcf go-update-v${currentRelease}-reports.tgz reports/"
					emailext (
						body: "This is an automated message from Jenkins regarding an update for v${currentRelease}. Please review the attached reports archive generated by the GO Update step. If they look correct, these reports need to be uploaded to the Reactome Drive at Reactome>Release>Release QA>v${currentRelease}. If they don't look correct, please email the developer running Release. \n\nThanks!",
						to: '$DEFAULT_RECIPIENTS',
						from: "${env.JENKINS_DEV_EMAIL}",
						subject: "GO Update Reports for v${currentRelease}",
						attachmentsPattern: "**/go-update-v${currentRelease}-reports.tgz"
					)
				}
			}
		}
					
	}
}
