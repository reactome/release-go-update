import groovy.json.JsonSlurper
// This Jenkinsfile is used by Jenkins to run the 'GOUpdate' step of Reactome's release.
// This step synchronizes Reactome's GO terms with Gene Ontology. 
// It requires that the 'UniProtUpdate' step has been run successfully before it can be run.
def currentRelease
def previousRelease
pipeline {
	agent any

	stages {
		// This stage checks that an upstream step, UniProtUpdate, was run successfully.
		stage('Check UniProtUpdate build succeeded'){
			steps{
				script{
					// Get current release number from directory
					currentRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1];
					previousRelease = (pwd() =~ /Releases\/(\d+)\//)[0][1].toInteger() - 1;
					// This queries the Jenkins API to confirm that the most recent build of 'UniProtUpdate' was successful.
					def uniprotStatusUrl = httpRequest authentication: 'jenkinsKey', validResponseCodes: "${env.VALID_RESPONSE_CODES}", url: "${env.JENKINS_JOB_URL}/job/${currentRelease}/job/Pre-Slice/job/UniProtUpdate/lastBuild/api/json"
					if (uniprotStatusUrl.getStatus() == 404) {
						error("UniProtUpdate has not yet been run. Please complete a successful build.")
					} else {
						def uniprotStatusJson = new JsonSlurper().parseText(uniprotStatusUrl.getContent())
						if (uniprotStatusJson['result'] != "SUCCESS"){
							error("Most recent UniProtUpdate build status: " + uniprotStatusJson['result'] + ". Please complete a successful build.")
						}
					}
				}
			}
		}
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
		// This stage builds the jar file using Maven.
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
		// This stage backs up the gk_central database after modification.
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
		// This stage archives the contents of the 'reports' folder generated by GO Update and sends them in an email to the default recipients list.
		stage('Post: Email GO Update Reports'){
			steps{
				script{
					sh "tar zcf go-update-v${currentRelease}-reports.tgz reports/"
					emailext (
						body: "Hello,\n\nThis is an automated message from Jenkins regarding an update for v${currentRelease}. The GO Update step has completed. Please review the reports attached to this email. If they look correct, these reports need to be uploaded to the Reactome Drive at Reactome>Release>Release QA>V${currentRelease}_QA>V${currentRelease}_QA_GO_Update_Reports. The URL to the new V${currentRelease}_QA_GO_Update_Reports folder also needs to be updated at https://devwiki.reactome.org/index.php/Reports_Archive under 'GO Update Reports'. Please add the older GO report URL to the 'Archived reports' section of the page. If they don't look correct, please email the developer running Release. \n\nThanks!",
						to: '$DEFAULT_RECIPIENTS',
						from: "${env.JENKINS_RELEASE_EMAIL}",
						subject: "GO Update Reports for v${currentRelease}",
						attachmentsPattern: "**/go-update-v${currentRelease}-reports.tgz"
					)
				}
			}
		}
		// All databases, logs, and data files generated by this step are compressed before moving them to the Reactome S3 bucket. 
		// All files are then deleted.
		stage('Post: Archive Outputs'){
			steps{
				script{
					def s3Path = "${env.S3_RELEASE_DIRECTORY_URL}/${currentRelease}/go_update"
					sh "mkdir -p databases/ data/"
					sh "mv --backup=numbered *_${currentRelease}_*.dump.gz databases/"
					sh "mv src/main/resources/go.obo data/"
					sh "mv src/main/resources/ec2go data/"
					sh "gzip data/* logs/*"
					sh "mv go-update-v${currentRelease}-reports.tgz data/"
					sh "aws s3 --no-progress --recursive cp databases/ $s3Path/databases/"
					sh "aws s3 --no-progress --recursive cp logs/ $s3Path/logs/"
					sh "aws s3 --no-progress --recursive cp data/ $s3Path/data/"
					sh "rm -r databases logs data reports"
				}
			}
		}						
	}
}
