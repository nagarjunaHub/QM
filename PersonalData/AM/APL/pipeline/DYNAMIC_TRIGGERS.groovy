/**
 * This pipeline is used for generating Gerrit Dynamic trigger files from AOSP and APP manifest files
 * The following parameters are required to run this pipeline script.
 * [Mandatory] StringParameter GERRIT_SERVER: GERRIT_HOST name including port number.
 * [Mandatory] StringParameter pipeline_node: Buildbots label for executing the pipeline.
 * [Mandatory] TextParameter BRANCHES: All the branch names to be considered from AOSP Manifest file.
 * 									   It also supports branch names in regex format.
 * 									   e.g t2k_s_* will work for all branch names starting with t2k_s_
 * [Mandatory] StringParameter APPS_MANIFESTS: App Manifests repo name
 * [Mandatory] StringParameter APPS_MANIFESTS_BRANCH: Branch name for App Manifests repo
 * [Mandatory] StringParameter AOSP_MANIFESTS: AOSP Manifests repo name
 * [Mandatory] StringParameter AOSP_MANIFESTS_BRANCH: Branch name for AOSP Manifests repo
 * [Mandatory] StringParameter APPS_MANIFEST_FILE: App Prebuilts Manifest file from AOSP Manifest repo
 * [Mandatory] StringParameter AOSP_MANIFEST_FILE: AOSP Manifest file from AOSP Manifest
 * [Optional] TextParameter AOSP_REPO_EXCLUDES: AOSP repo list to be excluded from Trigger file. Defaults to empty
 * [Optional] TextParameter APP_PREBUILTS_EXCLUDES: APP prebuilts repo list to be excluded from Trigger file. Defaults to empty
 * [Optional] TextParameter APP_REPO_EXCLUDES: APP source repo list to be excluded from Trigger file. Defaults to empty
 * [Optional] BooleanParameter FORCE_RUN: Enable for Pipeline Manual trigger. Defaults to false
 **/
@Library(['pipeline-global-library@app']) _
import com.eb.lib.aosp.aospUtils
jobPath = JOB_NAME.replace('/', '/jobs/')
archiveFloder = "${JENKINS_HOME}/jobs/${jobPath}/builds/${BUILD_NUMBER}/archive"
symLinkPath = "${JENKINS_HOME}/jobs/${jobPath}/${JOB_BASE_NAME}_latest"
commonlib = new aospUtils()
build_node_list = nodesByLabel label: pipeline_node
leastloadednode = ''
if (build_node_list) {
	leastloadednode = commonlib.getLeastLoadedNode(build_node_list.join(' ').tokenize(' '))
	print("leastloadednode: ${leastloadednode}")
}
pipeline {
	agent { node "${leastloadednode}" }
	options { timestamps () }
	stages {
		stage('Create DynamicTrigger Cfg Files') {
			steps {
				script {
					try {
						if(!(GERRIT_SERVER || pipeline_node || BRANCHES || APPS_MANIFESTS || APPS_MANIFESTS_BRANCH || AOSP_MANIFESTS || AOSP_MANIFESTS_BRANCH || APPS_MANIFEST_FILE || AOSP_MANIFEST_FILE)) {
							println("Error: One or more of the following Params are empty...")
							println("GERRIT_SERVER:${GERRIT_SERVER}")
							println("pipeline_node:${pipeline_node}")
							println("BRANCHES:${BRANCHES}")
							println("APPS_MANIFESTS:${APPS_MANIFESTS}")
							println("APPS_MANIFESTS_BRANCH:${APPS_MANIFESTS_BRANCH}")
							println("AOSP_MANIFESTS:${AOSP_MANIFESTS}")
							println("AOSP_MANIFESTS_BRANCH:${AOSP_MANIFESTS_BRANCH}")
							println("APPS_MANIFEST_FILE:${APPS_MANIFEST_FILE}")
							println("AOSP_MANIFEST_FILE:${AOSP_MANIFEST_FILE}")
							currentBuild.result = 'FAILURE'
							error "Error: One or more Jenkins parameters not defined..."
						}
						else if(FORCE_RUN == 'true' || env.GERRIT_PROJECT) {
							List<String> branches_list = params.BRANCHES.split()
							def changeLog = ''
							def changeLogFile = "changeLog.log"
							def allRepos = ''
							def allReposTriggerCfg = "allReposTriggerCfg.txt"
							def appsTriggerCfg = "appsVerifyTriggerCfg.txt"
							def aospTriggerCfg = 'aospDevelTriggerCfg.txt'
							if(FORCE_RUN == 'true') {
								currentBuild.displayName = "#${currentBuild.number} FORCE_RUN"
								print("Build triggered with FORCE_RUN")
							}
							else {
								currentBuild.displayName = "#${currentBuild.number} " + new File(GERRIT_PROJECT).name
								currentBuild.description = GERRIT_NEWREV
								println("GERRIT_NEWREV: ${GERRIT_NEWREV}")
								println("GERRIT_EVENT_ACCOUNT_NAME: ${GERRIT_EVENT_ACCOUNT_NAME}")
							}
							List<String> aospRepoExcludes = []
							List<String> appPrebuiltsExcludes = []
							List<String> appRepoExcludes = []
							if(!params.APP_REPO_EXCLUDES.isEmpty()) {
								appRepoExcludes = params.APP_REPO_EXCLUDES.split()
							}
							if(!params.APP_PREBUILTS_EXCLUDES.isEmpty()) {
								appPrebuiltsExcludes = params.APP_PREBUILTS_EXCLUDES.split()
							}
							if(!params.AOSP_REPO_EXCLUDES.isEmpty()) {
								aospRepoExcludes = params.AOSP_REPO_EXCLUDES.split()
							}
							println("appRepoExcludes Class: ${appRepoExcludes.getClass()}")
							println("aospRepoExcludes Class: ${aospRepoExcludes.getClass()}")
							println("appPrebuiltsExcludes Class: ${appPrebuiltsExcludes.getClass()}")
							apps_manifest_url = "${GERRIT_SERVER}/${APPS_MANIFESTS}"
							apps_manifest_name = new File(APPS_MANIFESTS).name
							aosp_manifest_url = "${GERRIT_SERVER}/${AOSP_MANIFESTS}"
							aosp_manifest_name = new File(AOSP_MANIFESTS).name
							checkout([$class: 'GitSCM', branches: [[name: "${APPS_MANIFESTS_BRANCH}"]], \
							userRemoteConfigs: [[url: "${apps_manifest_url}"]], \
							extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/${apps_manifest_name}"]]])
							checkout([$class: 'GitSCM', branches: [[name: "${AOSP_MANIFESTS_BRANCH}"]], \
							userRemoteConfigs: [[url: "${aosp_manifest_url}"]], \
							extensions: [[$class: 'RelativeTargetDirectory', relativeTargetDir: "${WORKSPACE}/${aosp_manifest_name}"]]])
							commonlib.createAppsDynamicTriggerCfg("${WORKSPACE}/${apps_manifest_name}", APPS_MANIFESTS_BRANCH, appRepoExcludes)
							commonlib.createAospDynamicTriggerCfg("${WORKSPACE}/${aosp_manifest_name}", AOSP_MANIFEST_FILE, APPS_MANIFEST_FILE, branches_list, aospRepoExcludes, appPrebuiltsExcludes)
							if(fileExists(appsTriggerCfg)) {
								allRepos += readFile appsTriggerCfg
							}
							if(fileExists(aospTriggerCfg)) {
								allRepos += readFile aospTriggerCfg
							}
							dir(WORKSPACE) {
								writeFile file: allReposTriggerCfg, text: allRepos
								archiveArtifacts artifacts: "*.txt", allowEmptyArchive: true, fingerprint: true
							}
							node('master') {
								changeLog = commonlib.getChangeLog(archiveFloder, symLinkPath, allReposTriggerCfg)
								print(changeLog)
							}
							dir(WORKSPACE) {
								writeFile file: changeLogFile, text: changeLog
								archiveArtifacts artifacts: changeLogFile, allowEmptyArchive: true, fingerprint: true
							}
						}
						else {
							currentBuild.result = 'ABORTED'
							error("Aborting the build due to invalid build trigger. \nPlease select FORCE_RUN for manual build...")
						}
					}  catch (err) {
						echo "Caught: ${err}"
						currentBuild.result = 'FAILURE'
					}
				}
			}
		}
		stage('Create/Update symlink') {
			agent { node 'master' }
			steps {
				script {
					try {
						print("Creating symlink in ${symLinkPath} for ${archiveFloder}")
						if(fileExists(archiveFloder)) {
							sh "ln -sfn ${archiveFloder} ${symLinkPath}"
							sh "ls -la ${symLinkPath}"
						}
						else {
							print("${archiveFloder} doesn't exists")
						}
					}  catch (err) {
						echo "Caught: ${err}"
						currentBuild.result = 'FAILURE'
					}
				}
			}
		}
	}
}