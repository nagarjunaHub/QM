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
        if (config.unit_test_targets && config[configRuntime.pipelineType][target_id].run_unit_tests == "true" && build_type.contains("userdebug")) {
          make_targets = String.format(" %s ", config.unit_test_targets)

          user_custom_build_env = config["supported_target_ids"][target_id]["user_custom_build_env"]
          lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)

          stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
          stackTargetFile = config.stacktarget_file
          build_target = [target_id,build_type].join("-")


          commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")


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
          aospBuildParallelMap[build_target] = commonlib.configureUnitTests(aosp_job_parameters)
        }
      }
    }
  }

  try {
      parallel aospBuildParallelMap
  } catch (FlowInterruptedException e) {
      throw e
  } catch (err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = "FAILURE"
      configRuntime.stage_results[STAGE_NAME] = "FAILURE"
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
