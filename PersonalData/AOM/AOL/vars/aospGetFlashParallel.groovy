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

  Map aospGetFlashParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospGetFlashParallelMap.failFast = true
  }

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

          commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

          // Creating AOSP development testing image publish to netshare
          if (configRuntime.pipelineType.contains("verify") && (configRuntime.get_flash_image == true)){
              configRuntime.get_flash_version = commonlib.aospGetFlashVersion(GERRIT_PROJECT, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER)
             def parameter_list = [
                  'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
                  'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
                  'BUILD_TYPE='+                build_type,
                  'TARGET_ID='+                 target_id,
                  'GET_FLASH_VERSION='+         configRuntime.get_flash_version,
                  'NET_SHAREDRIVE='+            configRuntime.NET_SHAREDRIVE_TABLE["get_flash"],
                  'BUILD_TOOL_URL='+            body.script.buildtools_url,
                  'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
                  'VERBOSE='+                   VERBOSE,
                  'BUILD_TARGET='+              build_target,
              ]

          // How to create flashimage
          try {
            parameter_list += ['FLASHIMAGE_CUSTOMIZED_METHOD='+ config[configRuntime.pipelineType][target_id]["flashimage"]["customized_method"]]
            parameter_list += ['FLASHIMAGE_SCRIPT='+ config[configRuntime.pipelineType][target_id]["flashimage"]["script"]]
          }
          catch (err) {
            parameter_list += ['FLASHIMAGE_CUSTOMIZED_METHOD=false']
            parameter_list += ['FLASHIMAGE_SCRIPT=none']
          }
              aospGetFlashParallelMap[build_target] = commonlib.configureGetFlash(parameter_list)
          }
      }
    }

    try {
      parallel aospGetFlashParallelMap
    } catch (FlowInterruptedException e) {
        throw e
    } catch(err) {
      configRuntime.TEST_STATUS = 'FAILURE'
      commonlib.__INFO(err.toString())
      currentBuild.result = "FAILURE"
      configRuntime.stage_results[STAGE_NAME] = "FAILURE"
      configRuntime.BUILD_STATUS = "FAILURE"
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+"%s",configRuntime.email_build_info)

    }

  }
}
