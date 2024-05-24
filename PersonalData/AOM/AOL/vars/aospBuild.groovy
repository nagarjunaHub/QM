import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  def target_id_list = configRuntime.supported_targets.tokenize(" ")

  def launched_aosp_jobs = [:]
  // Fail fast for parallel jobs per stage wise

  Map aospBuildParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospBuildParallelMap.failFast = true
  }
  def aospDevelBuildSyncParallelMap = [:]

  node(configRuntime.pipeline_node) {
    target_id_list.each { target_id ->
      configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
      configRuntime.build_type_list.each { build_type ->
          if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
            commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
            return
          }
          make_targets = String.format("%s %s %s", "droid", config["supported_target_ids"][target_id]["custom_env_vars"], config[configRuntime.pipelineType].pipeline_make_targets)

          if (configRuntime.pipelineType.contains("verify")) {
            if (configRuntime.require_clean_build == "true") {
              make_targets = String.format("%s %s", make_targets, "clean")
            } else {
              // Verify builds are incremental, and to make them accurate, build another make target "installclean"
              // Read the file build/soong/ui/build/cleanbuild.go from an AOSP workspace for details on installclean
              make_targets = String.format("%s %s", make_targets, "installclean")
            }
          }

          user_custom_build_env = config["supported_target_ids"][target_id]["user_custom_build_env"]
          lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)

          stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
          stackTargetFile = config.stacktarget_file
          build_target = [target_id,build_type].join("-")

          commonlib.onHandlingException("Getting Build Bot List"){
              // Get build variant IDs based on manifest release
              build_node_list = commonlib.getBuildNodes(stackTarget, stackTargetFile, VERBOSE)
          }

          commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

          last_build = String.format("%s/%s", configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType], config[configRuntime.pipelineType].latest_build_link_name)
          // Get the absolute path to the last build. We use this path later when we don't want to build vts again.
          node(configRuntime.build_variant[build_target].least_loaded_node) {
              last_build = sh(returnStdout: true, script:"""#!/bin/bash
                [[ ${VERBOSE} == "true" ]] && set -x
                [[ -e  ${last_build} ]] && readlink -f ${last_build} || echo /dev/null
              """).trim()
          }
          commonlib.__INFO("Last build is: ${last_build}")
          last_build_manifest = String.format("%s/%s.xml", last_build, target_id)

          // Creating AOSP build executors
          def aosp_job_parameters = [
              'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
              'MAKE_TARGET='+               make_targets,
              'LUNCH_TARGET='+              lunch_target,
              'DOCKER_IMAGE_ID='+           config.docker_image_id,
              'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
              'USER_CUSTOM_BUILD_ENV='+     user_custom_build_env,
              'DEV_NULL='+                  config.dev_null,
              'BUILD_TOOL_URL='+            body.script.buildtools_url,
              'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
              'VERBOSE='+                   VERBOSE,
              "CAUSED_BY="+                 [JOB_NAME,BUILD_NUMBER].join("/"),
              'BUILD_VTS='+                 config[configRuntime.pipelineType].build_vts,
              'VTS_MAKE_TARGET='+           config.vts?.vts_make_target?:'',
              'VTS_REPOSITORIES='+          config.vts?.vts_repositories?:'',
              'LAST_BUILD='+                last_build,
              'LAST_BUILD_MANIFEST='+       last_build_manifest,
              'PIPELINE_TYPE='+             configRuntime.pipelineType,
              'BUILD_TARGET='+              build_target,
              'BUILD_NAME='+                configRuntime.project_release_version,
          ]

          try {
            // Try to get "ota_gen" value in config file
            if (configRuntime.pipelineType.contains("snapshot")) {
              aosp_job_parameters += ['OTA_GEN='+ config[configRuntime.pipelineType][target_id]["ota"]["gen"]]
              aosp_job_parameters += ['OTA_CUSTOMIZED_METHOD='+ config[configRuntime.pipelineType][target_id]["ota"]["customized_method"]]
              aosp_job_parameters += ['OTA_SCRIPT='+ config[configRuntime.pipelineType][target_id]["ota"]["script"]]
            }
            else {
              aosp_job_parameters += ['OTA_GEN=false']
              aosp_job_parameters += ['OTA_CUSTOMIZED_METHOD=false']
              aosp_job_parameters += ['OTA_SCRIPT=none']
            }
          }
          catch (err) {
            // Handle the case "ota_gen" not available in config file
            aosp_job_parameters += ['OTA_GEN=false']
            aosp_job_parameters += ['OTA_CUSTOMIZED_METHOD=false']
            aosp_job_parameters += ['OTA_SCRIPT=none']
          }

          // Get swup configuration
          try {
            aosp_job_parameters += ['SWUP_GEN='+ config[configRuntime.pipelineType][target_id]["swup"]["gen"]]
            aosp_job_parameters += ['SWUP_SCRIPT='+ config[configRuntime.pipelineType][target_id]["swup"]["script"]]
          }
          catch (err) {
            aosp_job_parameters += ['SWUP_GEN=false']
            aosp_job_parameters += ['SWUP_SCRIPT=none']
          }

          // Get CCACHE configuration
          try {
            aosp_job_parameters += ['CCACHE_ENABLED='+ config[configRuntime.pipelineType][target_id]["ccache"]["enabled"]]
            aosp_job_parameters += ['CCACHE_EXEC='+ config[configRuntime.pipelineType][target_id]["ccache"]["exec"]]
            aosp_job_parameters += ['CCACHE_DIR='+ config[configRuntime.pipelineType][target_id]["ccache"]["dir"]]
            aosp_job_parameters += ['CCACHE_UMASK='+ config[configRuntime.pipelineType][target_id]["ccache"]["umask"]]
            aosp_job_parameters += ['CCACHE_MAX_SIZE='+ config[configRuntime.pipelineType][target_id]["ccache"]["max_size"]]
          }
          catch (err) {
            aosp_job_parameters += ['CCACHE_ENABLED=false']
            aosp_job_parameters += ['CCACHE_EXEC=none']
            aosp_job_parameters += ['CCACHE_DIR=none']
            aosp_job_parameters += ['CCACHE_UMASK=none']
            aosp_job_parameters += ['CCACHE_MAX_SIZE=none']
          }

          // How to create flashimage
          try {
            aosp_job_parameters += ['FLASHIMAGE_CUSTOMIZED_METHOD='+ config[configRuntime.pipelineType][target_id]["flashimage"]["customized_method"]]
            aosp_job_parameters += ['FLASHIMAGE_SCRIPT='+ config[configRuntime.pipelineType][target_id]["flashimage"]["script"]]
          }
          catch (err) {
            aosp_job_parameters += ['FLASHIMAGE_CUSTOMIZED_METHOD=false']
            aosp_job_parameters += ['FLASHIMAGE_SCRIPT=none']
          }
          
          aospBuildParallelMap['buildFor_'+build_target] = commonlib.configureBuild(aosp_job_parameters)

          //Creating Devel sync build output to build bot list
          if (configRuntime.pipelineType.contains("devel")) {
              def sync_timers=config[configRuntime.pipelineType].sync_timers?:'10'.trim()
              parameter_list = [
                  'BUILD_NODE_LIST='+           build_node_list,
                  'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
                  'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
                  'SOURCE_VOLUME_BASELINE='+    configRuntime.build_variant[build_target].source_volume_baseline,
                  'BUILD_TOOL_URL='+            body.script.buildtools_url,
                  'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
                  'REPO_MANIFEST_URL='+         config.repo_release_manifest_url,
                  'REPO_MANIFEST_REVISION='+    configRuntime.repo_dev_manifest_revision,
                  'REPO_MANIFEST_XML='+         config["supported_target_ids"][target_id]["repo_manifest_xml"],
                  'SYNC_TIMERS='+               sync_timers,
              ]

              def aosp_devel_sync = (DISABLE_AOSP_DEVEL_SYNC == "true") ? "disabled" : config[configRuntime.pipelineType]["aosp_devel_build_sync"]
              if ( aosp_devel_sync != "disabled") {
                aospDevelBuildSyncParallelMap['syncWSin_'+build_target] = commonlib.configureDevelBuildSync(parameter_list + ["SYNC_TIMES=start"])
              } else {
                commonlib.__INFO("Syncing devel workspace across buildbots is disabled. Enable it in " + PROJECT_CONFIG_FILE + " if you want.")
              }
          }
      }
    }

  }

  try {
    if (configRuntime.pipelineType.contains("devel") && aospDevelBuildSyncParallelMap) {
        parallel(
            "BUILD": {
                parallel aospBuildParallelMap
            },
            "SYNC": {
                parallel aospDevelBuildSyncParallelMap
            }
        )
    } else {
        parallel aospBuildParallelMap
    }
  } catch (FlowInterruptedException e) {
      throw e
  } catch (err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = "FAILURE"
      configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
      configRuntime.BUILD_STATUS = 'FAILURE'
      configRuntime.build_addinfo = "(AOSP Compilation). Error log: "

      target_id_list.each { target_id ->
        configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
        configRuntime.build_type_list.each { build_type ->
          build_target = [target_id,build_type].join("-")
          node(configRuntime.build_variant[build_target].least_loaded_node) {

            // Achieve build error logs
            sh """cp ${configRuntime.build_variant[build_target].source_volume}/out/error.log ${env.WORKSPACE}/${build_target}.error.log"""

            archiveArtifacts artifacts: "${build_target}.error.log", allowEmptyArchive: 'true'
            configRuntime.build_addinfo += String.format("%s%s%s", "${env.BUILD_URL}", "artifact/", "${build_target}.error.log")
          }
        }
      }
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
  }

}
