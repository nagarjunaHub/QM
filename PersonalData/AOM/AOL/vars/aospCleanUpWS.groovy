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

  def aospCleanUpWSParallelMap = [:]
  def aospCleanSelfWSParallelMap = [:]

  node(configRuntime.pipeline_node) {
    target_id_list.each { target_id ->
      configRuntime.build_type_list = config[configRuntime.pipelineType][target_id]["build_type_list"].tokenize(" ")
      configRuntime.build_type_list.each { build_type ->
          if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
            commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
            return
          }
          make_targets = String.format("%s+%s+%s", "droid", config["supported_target_ids"][target_id]["custom_env_vars"], config.pipeline_make_targets)
          user_custom_build_env = config["supported_target_ids"][target_id]["user_custom_build_env"]
          lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)

          stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
          stackTargetFile = config.stacktarget_file
          build_target = [target_id,build_type].join("-")
          try{
            if ( ! configRuntime.build_variant[build_target].least_loaded_node ){
              commonlib.__INFO("ERROR: There is no build node allocated for this build. It could be a failure at Prepare Pipeline or Prepare Workspace")
              return
            }
          } catch(err) {
            currentBuild.result = 'FAILURE'
            configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
            currentBuild.description = "No build bot found in PrepareWS!"
            return
          }
          commonlib.__INFO("Clean up workspace for ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")


          // Creating workspace Preparation/Publish/CleanUp Executors
          parameter_list = [
              'LEAST_LOADED_NODE='+               configRuntime.build_variant[build_target].least_loaded_node,
              'PROJECT_BRANCH='+                  configRuntime.project_branch,
              'PIPELINE_TYPE='+                   configRuntime.pipelineType,
              'SOURCE_VOLUME_BASELINE='+          configRuntime.build_variant[build_target].source_volume_baseline,
              'SOURCE_VOLUME='+                   configRuntime.build_variant[build_target].source_volume,
              'BUILD_TOOL_URL='+                  body.script.buildtools_url,
              'BUILD_TOOL_BRANCH='+               body.script.buildtools_branch,
              'VERBOSE='+                         VERBOSE,
              'RETAIN_WORKSPACE='+                configRuntime.retain_workspace,
              'BUILD_TARGET='+                    build_target,
          ]

          aospCleanUpWSParallelMap[build_target] = commonlib.configureWorkspace("clean", parameter_list)
          aospCleanSelfWSParallelMap[build_target] = commonlib.configureWorkspace("cleanself", parameter_list)

      }
    }

  }

  try {
    if ((configRuntime.BUILD_STATUS == "SUCCESS") && (configRuntime.TEST_STATUS == "SUCCESS")) {
        parallel aospCleanUpWSParallelMap
    } else {
        parallel aospCleanSelfWSParallelMap
    }
  } catch (FlowInterruptedException e) {
      throw e
  } catch(err) {
    commonlib.__INFO(err.toString())
    currentBuild.result = 'FAILURE'
    configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
    configRuntime.build_addinfo = "(CleanUp BTRFS Snapshot WS)!!!"
    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
  }

}
