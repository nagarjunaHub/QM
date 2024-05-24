import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime

  def get_flash_image_msg = "__get_flash__"
  def get_flash_image_msg_clean = "__get_flash_clean__"

  def gerrit_branch = ""
  node(configRuntime.pipeline_node) {
    // Checkout build tools into workspsace

    configRuntime.fresh_repo_workspace = env.FRESH_REPO_WORKSPACE ?: "false"
    configRuntime.supported_targets = config.supported_target_ids.keySet().join(" ")
    def target_id_list = configRuntime.supported_targets.tokenize(" ")

    // get all unique repo_manifest_xml from config
    def manifests_to_targets = [:]

    target_id_list.each { target_id ->
      // get repo_manifest_xml for target_id else from config[supported_targes]
      def repo_manifest_xml = config[configRuntime.pipelineType][target_id]["repo_manifest_xml"]?:config["supported_target_ids"][target_id]["repo_manifest_xml"]
      if (repo_manifest_xml == null) {
          error("repo_manifest_xml is not defined for target_id: ${target_id}")
      }
      if (manifests_to_targets[repo_manifest_xml] == null) {
        manifests_to_targets[repo_manifest_xml] = [target_id]
      } else {
        manifests_to_targets[repo_manifest_xml].add(target_id)
      }
    }

    configRuntime.affected_manifests = manifests_to_targets.keySet().join(" ").tokenize(" ")

    if ((env.GERRIT_BRANCH || env.GERRIT_REFNAME || env.GERRIT_PROJECT) && (env.FORCE_RUN == "false")) {
      gerrit_branch = env.GERRIT_BRANCH ?: env.GERRIT_REFNAME
      configRuntime.affected_manifests = commonlib.getAffectedManifests(VERBOSE, config.repo_manifest_url, configRuntime.repo_dev_manifest_revision, env.GERRIT_PROJECT, gerrit_branch, configRuntime.supported_targets).tokenize(" ")

      // get targetlist using affected_manifests in manifests_to_targets
      def target_list = []
      configRuntime.affected_manifests.each { manifest ->
          target_list.addAll(manifests_to_targets[manifest] ?: [])
      }
      configRuntime.supported_targets = target_list.unique().join(' ') // remove duplicates and make is as string as expected by pipeline
    }

    configRuntime.pipeline_tag="${configRuntime.pipelineType}_${config.project_branch}".toUpperCase()
    try {
        if (configRuntime.pipelineType.contains("verify")) {
          if (GERRIT_EVENT_TYPE == "wip-state-changed" && GERRIT_CHANGE_WIP_STATE == "true") {
            currentBuild.result = 'ABORTED'
            configRuntime.stage_results[STAGE_NAME] = "ABORTED"
            configRuntime.BUILD_STATUS = 'ABORTED'
            configRuntime.verify_score = 0
            currentBuild.description = 'WIP change. Hence aborting early.'
            error("WIP change. Hence aborting the job early.")
          }

          gerrit_refspec = env.GERRIT_REFSPEC?:''
          try{
            commonlib.validateSonarProperty(VERBOSE, GERRIT_HOST, GERRIT_PROJECT, GERRIT_REFSPEC, GERRIT_CHANGE_NUMBER, GERRIT_CHANGE_COMMIT_MESSAGE)
          } catch(err) {
            configRuntime.email_build_info = "\n\t- Sonar properties file check failed"
            commonlib.__INFO("Sonar properties file check failed")
            error("Sonar properties file check failed")
          }

          gerrit_email= env.GERRIT_EVENT_ACCOUNT_EMAIL?:env.GERRIT_PATCHSET_UPLOADER_EMAIL
          configRuntime.email_build_info = "\n\t-Change: ${GERRIT_CHANGE_URL}\n\t-Patchset: ${GERRIT_PATCHSET_NUMBER} (${GERRIT_REFSPEC})\n\t-Project: ${GERRIT_PROJECT}\n\t-Branch: ${GERRIT_BRANCH}\n\t-Triggered By: ${gerrit_email}"

          configRuntime.build_description = "${env.GERRIT_PROJECT}-${env.GERRIT_BRANCH}: "

          configRuntime.email_recipients = config.email_recipients + commonlib.getReviewersEmailFromChangeNumber(GERRIT_HOST, GERRIT_CHANGE_NUMBER)
          configRuntime.email_recipients = "${configRuntime.email_recipients},${GERRIT_CHANGE_OWNER_EMAIL},${GERRIT_PATCHSET_UPLOADER_EMAIL}".tokenize(',').unique().join(',')

          // If the change that triggered a commit has dependent changes, collect them in this variable.
          def currentChangeDependencies = commonlib.gerritGetDependentChanges(VERBOSE, GERRIT_CHANGE_NUMBER, GERRIT_HOST)

          configRuntime.dependencies = commonlib.gerritGetDependencies(VERBOSE, GERRIT_CHANGE_COMMIT_MESSAGE, GERRIT_HOST)
          def all_dependencies = currentChangeDependencies + " " + configRuntime.dependencies
          configRuntime.affected_projects = commonlib.getAffectedProjects(VERBOSE, GERRIT_HOST, all_dependencies)
          commonlib.__INFO("Affected_projects as in gerrit: " + configRuntime.affected_projects)
          if (configRuntime.dependencies != "") {
              configRuntime.dependencies = configRuntime.dependencies + " " + currentChangeDependencies
              commonlib.__INFO("Dependencies: " + configRuntime.dependencies)
              configRuntime.verify_message = configRuntime.verify_message + "\n\n * Info:\n\t Depends on: " + configRuntime.dependencies.replace("true","up-to-date").replace("false","patchset-out-of-date")
          }
          if ( GERRIT_EVENT_TYPE == "comment-added"){
              def gerrit_comment_text = commonlib.getGerritEventMsg(GERRIT_EVENT_COMMENT_TEXT)
              commonlib.__INFO("comment: " + gerrit_comment_text)
              if (gerrit_comment_text.contains(get_flash_image_msg) || gerrit_comment_text.contains(get_flash_image_msg_clean)){
                  configRuntime.get_flash_image = true
              }
              if (gerrit_comment_text.contains(get_flash_image_msg_clean)){
                  configRuntime.get_flash_image_clean = true
              }
              if (gerrit_comment_text.contains("build_on_node")) {
                configRuntime.build_on_node = commonlib.getBuildOnNodeFromGerritEventMsg(GERRIT_EVENT_COMMENT_TEXT)
                commonlib.__INFO("build_on_node requested: " + configRuntime.build_on_node)
              }
              if (gerrit_comment_text.contains("__retrigger_clean__")) {
                configRuntime.require_clean_build = "true"
                commonlib.__INFO("Clean build requested via gerrit comment.")
              }
              if (gerrit_comment_text.contains("__retain_workspace__")) {
                configRuntime.retain_workspace = "true"
                commonlib.__INFO("Retain workspace requested.")
              }
              if (gerrit_comment_text.contains("__fresh_repo_workspace__")) {
                configRuntime.fresh_repo_workspace = "true"
                commonlib.__INFO("Fresh repo workspace requested.")
              }
          }
          def vmsgtemp = "Automated verification pipeline has started: \n\t\t${env.BUILD_URL}consoleFull"
          if (configRuntime.get_flash_image == true){
              vmsgtemp = "Automated getting flashing image has started: \n\t\t${env.BUILD_URL}consoleFull"
              commonlib.gerrit_PostComment(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, vmsgtemp)
          } else {
              commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, vmsgtemp, 0) // Reset verify score.
          }

          vmsgtemp = commonlib.isCommitRebasable(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PROJECT, GERRIT_BRANCH)
          if (vmsgtemp != "") {
              configRuntime.verify_message = configRuntime.verify_message + "\n\n * Warning:\n\t Review commit is not on top of the latest commit of the branch (REBASE)"
          }

          vmsgtemp = commonlib.isJiraTicket(GERRIT_CHANGE_COMMIT_MESSAGE)
          if (vmsgtemp != "") {
              commonlib.__INFO("TRACING ID: " + vmsgtemp)
              configRuntime.verify_message = configRuntime.verify_message + "\n\n * Summary:\n\t [OK] TRACING ID: " + vmsgtemp
          } else {
              commonlib.__INFO("TRACING ID: NOT FOUND!" )
              configRuntime.verify_message = configRuntime.verify_message + "\n\n * Summary:\n\t [  ] TRACING ID: NOT FOUND!"
              //configRuntime.verify_score = -1
          }

        } else if(configRuntime.pipelineType.contains("devel")) {
            commonlib.__INFO("devel pipeline running here ")
            commonlib.__INFO("configRuntime.affected_manifests: " + configRuntime.affected_manifests)
            //Generate revision based xml and publish if any changes and use it for the build else skip the build.
            def aospUpdateDevelXmlMap = [:]
            configRuntime.affected_manifests.each { manifest ->
              def build_target = manifest.replace(".xml","")
              def build_type = 'userdebug'
              def stackTargetFile = "resources/"+config.stacktarget_file
              def stackTarget = "StackTarget_${configRuntime.project_branch}_${build_target}_userdebug"
              def defaultVariantHost= sh(returnStdout:true, script:"""#!/bin/bash -e
                host=`git archive --remote=${config.repo_manifest_url} ${config.project_branch}  ${stackTargetFile}| tar -xO | grep "${stackTarget}"| cut -d' ' -f 1 | head -1`
                echo \$host
                """).trim()
              def updateDevelXmlWs = "${config.ssd_root}/${stackTarget}/${configRuntime.project_branch}_${configRuntime.pipelineType}_src_AOSP-updateDevelWs"
              def develWsBaseline = commonlib.getSourceBaselineName(configRuntime.project_branch, configRuntime.pipelineType, updateDevelXmlWs)
              //if defaultVariantHost is empty then continue
              if (defaultVariantHost == "") {
                commonlib.__INFO("Default variant host is empty for ${build_target} and ${configRuntime.project_branch}")
                return
              }
              def parameter_list = [
                  'LEAST_LOADED_NODE='+               defaultVariantHost,
                  'SOURCE_VOLUME_BASELINE='+          develWsBaseline,
                  'SOURCE_VOLUME='+                   updateDevelXmlWs,
                  'VERBOSE='+                         VERBOSE,
                  'RETAIN_WORKSPACE='+                configRuntime.retain_workspace,
                  'BUILD_TARGET='+                    build_target,
                  'PIPELINE_TYPE='+                   configRuntime.pipelineType,
                  'REPO_RELEASE_MANIFEST_URL='+       config.repo_release_manifest_url,
                  'REPO_RELEASE_MANIFEST_REVISION='+  configRuntime.repo_dev_manifest_revision,
                  'REPO_MANIFEST_XML='+               manifest,
                  'REPO_MANIFEST_URL='+               config.repo_manifest_url,
                  'REPO_MANIFEST_REVISION='+          config.project_branch,
                  'PROJECT_RELEASE_VERSION='+         configRuntime.project_release_version,
              ]
              aospUpdateDevelXmlMap['Update__'+manifest] = commonlib.updateDevelXml("prepare", parameter_list)
            }
            // if aospUpdateDevelXmlMap not empty then run this in parallel in try catch
            if (aospUpdateDevelXmlMap.size() > 0) {
              try {
                parallel aospUpdateDevelXmlMap
                // check diff between rel_manifest dev_manifest for all ${target_id}.xml files if there is no new dev_released then ABORT the Devel build
                def is_devel_required=''
                configRuntime.affected_manifests.each { manifest ->
                    is_devel_required = is_devel_required + commonlib.isDevelRequired(VERBOSE, config.repo_release_manifest_url, configRuntime.repo_dev_manifest_revision, configRuntime.pipeline_tag, manifest)
                }
                if ((! is_devel_required) && (env.FORCE_RUN == "false")){
                  currentBuild.result = 'ABORTED'
                  configRuntime.stage_results[STAGE_NAME] = "ABORTED"
                  configRuntime.BUILD_STATUS = 'ABORTED'
                  commonlib.__INFO("No new changes,Devel build not required ==> SKIP DEVEL BUILD!!!")
                  currentBuild.description = "SKIPPED: No new changes"
                  return
                }
              } catch (err) {
                currentBuild.result = "FAILURE"
                configRuntime.stage_results[STAGE_NAME] = "FAILURE"
                commonlib.__INFO("Failed to update devel xmls")
                commonlib.__INFO(err.toString())
                configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+'%s',"Failed to update devel xmls and error message is: \n"+"%s",configRuntime.email_build_info)
                return
              }
            }
        } else if(configRuntime.pipelineType.contains("snapshot") && ! configRuntime.project_branch.contains("rel")) {
              configRuntime.devel_tag = "DEVEL_${config.project_branch}".toUpperCase()
              commonlib.__INFO("DEVEL_TAG: " + configRuntime.devel_tag)
              configRuntime.repo_dev_manifest_revision = sh(returnStdout:true, script:"""#!/bin/bash -e
    git ls-remote ${config.repo_release_manifest_url} ${configRuntime.devel_tag} | cut -f1
    """).trim()
              //if sync_revision is empty, then fail the build
              if (configRuntime.repo_dev_manifest_revision == "") {
                  commonlib.__INFO("DEVEL_TAG not present, ABORTED the build")
                  currentBuild.result = 'ABORTED'
                  configRuntime.stage_results[STAGE_NAME] = "ABORTED"
                  configRuntime.BUILD_STATUS = 'ABORTED'
                  configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),"DEVEL_TAG not present, ABORTED the build"+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
                  return
              }
            configRuntime.email_build_info = configRuntime.project_release_version
            // check diff between rel_manifest dev_manifest for all ${target_id}.xml files if there is no new dev_released then ABORT the Snapshot build
            target_id_list = configRuntime.supported_targets.tokenize(" ")
            def is_devel_released=''
            if (FORCE_RUN == "false"){
                target_id_list.each { target_id ->
                    is_devel_released = is_devel_released + commonlib.isDevelReleased(VERBOSE, config.repo_release_manifest_url, configRuntime.devel_tag, configRuntime.repo_rel_manifest_revision, config["supported_target_ids"][target_id]["repo_manifest_xml"])
                }
            }
            if ((! is_devel_released) && (FORCE_RUN == "false")){
                currentBuild.result = 'ABORTED'
                configRuntime.stage_results[STAGE_NAME] = "ABORTED"
                configRuntime.BUILD_STATUS = 'ABORTED'
                configRuntime.verify_score = 0
                commonlib.__INFO("No new Devel release ==> SKIP SNAPSHOT BUILD!!!")
                currentBuild.description = "SKIPPED: No Devel Rel"
                return
            }
            user_custom_build_env = ["BUILD_VERSION="+configRuntime.project_release_version].join(' ')
        }

        if (! configRuntime.supported_targets) {
            currentBuild.result = 'ABORTED'
            configRuntime.stage_results[STAGE_NAME] = "ABORTED"
            configRuntime.BUILD_STATUS = 'ABORTED'
            configRuntime.verify_score = 0

            commonlib.__INFO("${env.GERRIT_PROJECT} is not supported in ${gerrit_branch} ==> SKIP!!!")
            currentBuild.description = "${gerrit_branch} not supported in ${env.GERRIT_PROJECT}. Contact CI team."
            return
        }
    } catch (FlowInterruptedException e) {
        throw e
    } catch(err) {
        commonlib.__INFO(err.toString())
        currentBuild.result = "FAILURE"
        configRuntime.stage_results[STAGE_NAME] = "FAILURE"
        configRuntime.BUILD_STATUS = "FAILURE"
        configRuntime.build_addinfo = "(PreparePipeline)"
        configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+"%s",configRuntime.email_build_info)
      }
    }

    // Each stage is run if this variable remains set to SUCCESS
    // When a stage fails, this is set to FAILURE, so further stages are not run.
    currentBuild.result = currentBuild.result ?: 'SUCCESS'
    commonlib.__INFO("From Preparepipeline stage")
    commonlib.printConfigRuntimeYaml(configRuntime)

    // Used only in verify pipelines.
    if (currentBuild.result == 'ABORTED') {
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),currentBuild.description+"%s",configRuntime.email_build_info)
      currentBuild.result = 'ABORTED'
      configRuntime.stage_results[STAGE_NAME] = "ABORTED"
      configRuntime.BUILD_STATUS = 'ABORTED'
      configRuntime.verify_score = 0
    }
}
