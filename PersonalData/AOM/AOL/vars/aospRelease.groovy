import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import com.eb.lib.aosp.PipelineEnvironment
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config

  def configRuntime = body.script.configRuntime
  def target_id_list = configRuntime.supported_targets.tokenize(" ")

  def aospReleaseParallelMap = [:]
  def artifactoryUploadParallelMap = [:]
  def appReleaseParallelMap = [:]

  def launched_aosp_jobs = [:]
  node(configRuntime.pipeline_node) {
      target_id_list.each { target_id ->
        configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
        configRuntime.build_type_list.each { build_type ->
            if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
              commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
              return
            }

            stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
            stackTargetFile = config.stacktarget_file
            build_target = [target_id,build_type].join("-")
            lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)
            if (config.prebuilt_release_required == null) {
              configRuntime.prebuilt_release_name = "n/a"
            } else {
              configRuntime.prebuilt_release_name = [config.project_line,config.project_type,config.android_version,config.branch_identifier,"prebuilt",configRuntime.pipelineType].join("_").trim().toUpperCase() + "/" + lunch_target
            }
            commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

            // Creating Releasing Excutors
            if (! configRuntime.pipelineType.contains("verify")){
                parameter_list = [
                    'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
                    'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
                    'LUNCH_TARGET='+              lunch_target,
                    'PIPELINE_TYPE='+             configRuntime.pipelineType,
                    'BUILD_TYPE='+                build_type,
                    'REPO_MANIFEST_XML='+         config["supported_target_ids"][target_id]["repo_manifest_xml"],
                    'PROJECT_RELEASE_VERSION='+   configRuntime.project_release_version,
                    'PREBUILT_RELEASE_NAME='+     configRuntime.prebuilt_release_name,
                    'NET_SHAREDRIVE='+            configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType],
                    'REPO_MANIFEST_RELEASE='+     config.repo_release_manifest_url,
                    'BUILD_TOOL_URL='+            body.script.buildtools_url,
                    'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
                    'VERBOSE='+                   VERBOSE,
                    'FILES_TO_PUBLISH='+          config[configRuntime.pipelineType].files_to_publish,
                    'LINK_NAME='+                 config[configRuntime.pipelineType].latest_build_link_name,
                    'BUILD_TARGET='+              build_target,
                    'RUN_PROGUARD_UPLOAD='+       config[configRuntime.pipelineType][target_id].run_proguard_upload?:'',
                ]
                if (configRuntime.pipelineType.contains("snapshot")){
                    add_params = [
                        'REPO_MANIFEST_RELEASE_REVISION='+    configRuntime.repo_rel_manifest_revision
                    ]
                } else {
                    add_params = [
                        'REPO_MANIFEST_RELEASE_REVISION='+    configRuntime.repo_dev_manifest_revision
                    ]
                }
                if ( configRuntime.project_branch =~ 'rel'){
                  add_params = [
                      'REPO_MANIFEST_RELEASE_REVISION='+    configRuntime.project_branch
                  ]
                }
                try {
                    // Try to get "ota_gen" value in config file
                    if (configRuntime.pipelineType.contains("snapshot")) {
                        add_params += ['OTA_PUBLISH='+ config[configRuntime.pipelineType][target_id]["ota"]["publish"]]
                    } else {
                        add_params += ['OTA_PUBLISH=false']
                    }
                }
                catch (err) {
                    // Handle the case "ota_gen" not available in config file
                    add_params += ['OTA_PUBLISH=false']
                }

                // Get swup configuration
                try {
                    if (configRuntime.pipelineType.contains("snapshot")) {
                        add_params += ['SWUP_PUBLISH=' + config[configRuntime.pipelineType][target_id]["swup"]["publish"]]
                    } else {
                        add_params += ['SWUP_PUBLISH=false']
                    }
                }
                catch (err) {
                    add_params += ['SWUP_PUBLISH=false']
                }
                aospReleaseParallelMap['aosp_rel_for'+build_target] = commonlib.configureRelease(parameter_list + add_params)
            }

            // Creating App Releasing Excutors
            if (configRuntime.pipelineType.contains("snapshot")){
                parameter_list = [
                        'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
                        'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
                        'PIPELINE_TYPE='+             configRuntime.pipelineType,
                        'PROJECT_RELEASE_VERSION='+   configRuntime.project_release_version,
                        'NET_SHAREDRIVE='+            configRuntime.NET_SHAREDRIVE_TABLE["apps"],
                        'BUILD_TOOL_URL='+            body.script.buildtools_url,
                        'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
                        'VERBOSE='+                   VERBOSE,
                        'APP_FILES_TO_PUBLISH='+      config[configRuntime.pipelineType].app_files_to_publish,
                ]
                appReleaseParallelMap['app_rel_for'+build_target] = commonlib.configureAppRelease(parameter_list + add_params)
                if (build_type.contains("userdebug") && config[configRuntime.pipelineType]['publish_to_artifactory']?:'' == 'enabled') {
                    artifactory = config.artifactory?:[:]
                    artifactory_lib_publish_job = artifactory.job?:""
                    if (artifactory_lib_publish_job != "") {
                      // preparing to publish AOSP libs to Artifactory
                      def artifactory_job_parameters = [
                              'BUILDBOT=' +                 configRuntime.build_variant[build_target].least_loaded_node,
                              'SSD_PATH=' +                 configRuntime.build_variant[build_target].source_volume,
                              'SNAPSHOT_VERSION=' +         configRuntime.project_release_version,
                              'PROJECT_CONFIG_FILE=' +      artifactory.config_file,
                              'BUILD_TOOL_URL='+            body.script.buildtools_url,
                              'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch
                      ]
                      artifactoryUploadParallelMap['artifact_upload_'+build_target] = {
                          node(configRuntime.build_variant[build_target].least_loaded_node) {
                              commonlib.__INFO("Running artifactoryUploadParallelMap")
                              commonlib.eb_build(job: artifactory_lib_publish_job, wait: false, propagate: false, parameters: artifactory_job_parameters)
                          }
                      }
                    } else {
                      commonlib.__INFO("Skipping artifactoryUpload as config prop artifactory_lib_publish_job is not set.")
                    }
                }
            }

        }
      }
    try {
      def all_parallel = [:]
      all_parallel.putAll(aospReleaseParallelMap)
      all_parallel.putAll(appReleaseParallelMap)
      all_parallel.putAll(artifactoryUploadParallelMap)
      parallel all_parallel

      if (config.update_release_info == "true" && configRuntime.pipelineType.contains("snapshot") ) {

        // In t2k, updating release_info_file is handled by release pipeline. But in asterix2, and skilfish, this should be done by aosp snapshot pipeline.
        // Release info file is used to compile the release notes, and without this file, release notes  are empty.
        // Currently, theres only one type of build in these projects - nightly. But in t2k, it is nightly, weekly, and pi release.
        // TODO: Hence, nightly is hardcoded below. Also, the branch name "master" is wrong, and should be improved
        def  branch = (configRuntime.project_branch =~ 'rel') ? configRuntime.project_branch : 'master'
        sh """#!/bin/bash -xe
          source ${new PipelineEnvironment(this).loadBashLibs()} && \
          prod_update_release_info ${config.release_info_file} ${branch} nightly aosp ${configRuntime.project_release_version} ${configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType]}

          [ -f ${config.release_info_file}_prev_tmp ] && mv -f ${config.release_info_file}_prev_tmp ${config.release_info_file}_prev || true
          [ -f ${config.release_info_file}_tmp ] && mv -f ${config.release_info_file}_tmp ${config.release_info_file} || true
        """
      }

      // copy archived *.xml files to the release_info_file directory
      copyArtifacts(
        projectName: env.JOB_NAME,
        filter: '*.xml',
        fingerprintArtifacts: true,
        selector: specific(env.BUILD_NUMBER)
      )
      def checkout_branch = configRuntime.pipelineType.contains("snapshot")? configRuntime.repo_rel_manifest_revision : configRuntime.repo_dev_manifest_revision
      checkout_branch = configRuntime.project_branch =~ 'rel' ? configRuntime.project_branch : checkout_branch
      sh """#!/bin/bash -xe
        rm -rf ${WORKSPACE}/tmp_clone_release_manifest || true
        git clone ${config.repo_release_manifest_url} ${WORKSPACE}/tmp_clone_release_manifest
        pushd ${WORKSPACE}/tmp_clone_release_manifest &> /dev/null
        git checkout ${checkout_branch}
        cp -f ${WORKSPACE}/*.xml ${WORKSPACE}/tmp_clone_release_manifest/
        if [[ "x\$(git diff)" != "x" ]]; then
            git add *.xml
            git commit -m \"${env.USER} ${configRuntime.pipelineType} release: ${configRuntime.project_release_version}\"
        fi
        git tag -d ${configRuntime.pipeline_tag} || echo "Tag not found ${configRuntime.pipeline_tag}"
        git push \$(git remote) :refs/tags/${configRuntime.pipeline_tag} || true
        git tag ${configRuntime.project_release_version}
        git tag -f ${configRuntime.pipeline_tag}
        git push --tags \$(git remote) HEAD:refs/heads/${checkout_branch}
        popd &> /dev/null
      """

    } catch (FlowInterruptedException e) {
        throw e
    } catch(err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = 'FAILURE'
      configRuntime.stage_results[STAGE_NAME] = "FAILURE"
      configRuntime.RELEASE_STATUS = "FAILURE"
      configRuntime.build_addinfo = "(Release)!!!"
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
    }
  }
}
