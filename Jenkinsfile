// This Jenkinsfile is used by Jenkins to run the 'GO Update' step of Reactome's release.
// This step synchronizes Reactome's GO terms with Gene Ontology. 

import groovy.json.JsonSlurper
import org.reactome.release.jenkins.utilities.Utilities

// Shared library maintained at 'release-jenkins-utils' repository.
def utils = new Utilities()

pipeline {
	agent any

	stages {
		// This stage checks that an upstream step, UniProt Update, was run successfully.
		stage('Check UniProt Update build succeeded'){
			steps{
				script{
					utils.checkUpstreamBuildsSucceeded("Pre-Slice/job/UniProtUpdate")
				}
			}
		}
		// Download go.obo and ec2go files from GO.
		stage('Setup: Download go.obo and ec2go files'){
			steps{
				script{
					sh "wget -q http://current.geneontology.org/ontology/go.obo"
					sh "wget -q http://current.geneontology.org/ontology/external2go/ec2go"
					sh "mv go.obo src/main/resources/"
					sh "mv ec2go src/main/resources/"
				}
			}
		}
		// This stage backs up the gk_central database before it is modified.
		stage('Setup: Back up gk_central before modifications'){
			steps{
				script{
					withCredentials([usernamePassword(credentialsId: 'mySQLCuratorUsernamePassword', passwordVariable: 'pass', usernameVariable: 'user')]){
						utils.takeDatabaseDumpAndGzip("${env.GK_CENTRAL_DB}", "go_update", "before", "${env.CURATOR_SERVER}")
					}
				}
			}
		}
		// This stage builds the jar file using Maven.
		stage('Setup: Build jar file'){
			steps{
				script{
					utils.buildJarFile()
				}
			}
		}
		// This stage executes the GO Update jar file. 
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
						utils.takeDatabaseDumpAndGzip("${env.GK_CENTRAL_DB}", "go_update", "after", "${env.CURATOR_SERVER}")
					}
				}
			}
		}
		// This stage archives the contents of the 'reports' folder generated by GO Update and sends them in an email to the default recipients list.
		stage('Post: Email GO Update Reports'){
			steps{
				script{
					def releaseVersion = utils.getReleaseVersion()
					def goUpdateReportsFile = "go-update-v${releaseVersion}-reports.tgz"
					sh "tar -zcf ${goUpdateReportsFile} reports/"
					
					def emailSubject = "GO Update Reports for v${releaseVersion}"
					def emailBody = "Hello,\n\nThis is an automated message from Jenkins regarding an update for v${releaseVersion}. The GO Update step has completed. Please review the reports attached to this email. If they look correct, these reports need to be uploaded to the Reactome Drive at Reactome>Release>Release QA>V${releaseVersion}_QA>V${releaseVersion}_QA_GO_Update_Reports. The URL to the new V${releaseVersion}_QA_GO_Update_Reports folder also needs to be updated at https://devwiki.reactome.org/index.php/Reports_Archive under 'GO Update Reports'. Please add the current GO report wiki URL to the 'Archived reports' section of the page. If the reports don't look correct, please email the developer running Release. \n\nThanks!"
					utils.sendEmailWithAttachment("${emailSubject}", "${emailBody}", "${goUpdateReportsFile}")
				}
			}
		}
		// All databases, logs, and data files generated by this step are compressed before moving them to the Reactome S3 bucket. 
		// All files are then deleted.
		stage('Post: Archive Outputs'){
			steps{
				script{
					def releaseVersion = utils.getReleaseVersion()
					def dataFiles = ["src/main/resources/go.obo", "src/main/resources/ec2go", "go-update-v${releaseVersion}-reports.tgz"]
					// GO Update log files are already in a folder called 'logs', so an empty list is passed.
					def logFiles = []
					def foldersToDelete = []
					utils.cleanUpAndArchiveBuildFiles("go_update", dataFiles, logFiles, foldersToDelete)
				}
			}
		}
	}
}

