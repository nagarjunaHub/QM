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

  Map aospPublishWSParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospPublishWSParallelMap.failFast = true
  }
  def aospDevelFinalBuildSyncParallelMap = [:]

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

          commonlib.onHandlingException("Getting Build Bot List"){
              // Get build variant IDs based on manifest release
              build_node_list = commonlib.getBuildNodes(stackTarget, stackTargetFile, VERBOSE)
          }

          if (! build_node_list){
              currentBuild.result = 'ABORTED'
              configRuntime.stage_results[STAGE_NAME] = 'ABORTED'
              commonlib.__INFO("No Build Bot Found For ${configRuntime.project_branch} ==> SKIP!!!")
              return
          }

          commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

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

          aospPublishWSParallelMap[build_target] = commonlib.configureWorkspace("publish", parameter_list)

          //Creating Devel sync build output to build bot list
          if (configRuntime.pipelineType.contains("devel")) {
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
              ]

              def aosp_devel_sync = (DISABLE_AOSP_DEVEL_SYNC == "true") ? "disabled" : config[configRuntime.pipelineType]["aosp_devel_build_sync"]
              if ( aosp_devel_sync != "disabled") {
                aospDevelFinalBuildSyncParallelMap[build_target] = commonlib.configureDevelBuildSync(parameter_list + ["SYNC_TIMES=final"])
              } else {
                commonlib.__INFO("Syncing devel workspace across buildbots is disabled. Enable it in " + PROJECT_CONFIG_FILE + " if you want.")
              }
          }

      }
    }
  }

try {
  
    if (configRuntime.pipelineType.contains("devel") && aospPublishWSParallelMap && aospDevelFinalBuildSyncParallelMap) {
        parallel(
            "PUBLISH": {
                parallel aospPublishWSParallelMap
            },
            "SYNC": {
                parallel aospDevelFinalBuildSyncParallelMap
            }
        )
    } else if ( configRuntime.pipelineType.contains("devel") && aospDevelFinalBuildSyncParallelMap ){
        parallel aospDevelFinalBuildSyncParallelMap
    } else {
        parallel aospPublishWSParallelMap
    }
  } catch (FlowInterruptedException e) {
    throw e
  } catch(err) {
    commonlib.__INFO(err.toString())
    currentBuild.result = 'FAILURE'
    config.RELEASE_STATUS = "FAILURE"
    configRuntime.BUILD_STATUS = "FAILURE"
    configRuntime.stage_results[STAGE_NAME] = "FAILURE"
    configRuntime.build_addinfo = "(Publish BTRFS Snapshot WS)!!!"
    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
  }

}
