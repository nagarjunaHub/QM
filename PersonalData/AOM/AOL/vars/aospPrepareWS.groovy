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

  def get_flash_image_msg_clean = "__get_flash_clean__"

  Map aospPrepareWSParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospPrepareWSParallelMap.failFast = true
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

        // We collect build target + variant specific variables in this map, and use them throughout the pipeline.
        configRuntime.build_variant[build_target] = [:]

        configRuntime.build_variant[build_target].source_volume = "${config.ssd_root}/${stackTarget}/${configRuntime.project_branch}_${configRuntime.pipelineType}_src_AOSP-" + currentBuild.number

        // Get source baseline folder from latest stable build
        configRuntime.build_variant[build_target].source_volume_baseline = commonlib.getSourceBaselineName(configRuntime.project_branch, configRuntime.pipelineType, configRuntime.build_variant[build_target].source_volume)

        if (configRuntime.project_branch =~ 'rel'){
          String replaced_rel_branch = configRuntime.project_branch.replaceAll('_\\d+\\.\\d+', '')
          configRuntime.build_variant[build_target].source_volume = configRuntime.build_variant[build_target].source_volume.replaceAll('_\\d+\\.\\d+', '')
          configRuntime.build_variant[build_target].source_volume_baseline = configRuntime.build_variant[build_target].source_volume_baseline.replaceAll('_\\d+\\.\\d+', '')
        }

        commonlib.onHandlingException("Getting Build Bot List"){
            // Get build variant IDs based on manifest release
            build_node_list = commonlib.getBuildNodes(stackTarget, stackTargetFile, VERBOSE)
        }

        if (! build_node_list){
            currentBuild.result = 'ABORTED'
            configRuntime.stage_results[STAGE_NAME] = "ABORTED"
            configRuntime.BUILD_STATUS = 'ABORTED'
            configRuntime.verify_score = 0
            commonlib.__INFO("No Build Bot Found For ${configRuntime.project_branch} ==> SKIP!!!")
            return
        }

        // If configRuntime.build_on_node is set via (job param BUILD_ON_NODE or when comment-text in gerrit specified where to build)
        // then we reserve this node only for this job, and the load balancing function won't consider this node
        // for any other AOSP build jobs. When we are done, we will remove the "RESERVED" label.
        // This is the only place we find the node where the pipeline shud run for this target+buildtype combo.
        try {
          while(true) {
            if (configRuntime.build_on_node != "") {
              if (commonlib.nodeHasLabel(configRuntime.build_on_node, "RESERVED")) {
                commonlib.__INFO(configRuntime.build_on_node + " is already RESERVED. Sleeping for 2 minutes before checking again..")
                sleep(time:2, unit:"MINUTES")
                continue
              }
              commonlib.setNodeLabel(configRuntime.build_on_node, "RESERVED")
              configRuntime.build_variant[build_target].least_loaded_node = configRuntime.build_on_node
              break
            } else {
              configRuntime.build_variant[build_target].least_loaded_node =  commonlib.getBestBuildNode(build_node_list.tokenize(' '), configRuntime.build_variant[build_target].source_volume_baseline, configRuntime.pipelineType)
              if (configRuntime.build_variant[build_target].least_loaded_node == "_na_") {
                commonlib.__INFO("All nodes seem to be RESERVED. Sleeping for 2 minutes before checking again..")
                sleep(time:2, unit:"MINUTES")
              } else {
                break
              }
            }
          }
        } catch (FlowInterruptedException e) {
          throw e
        } catch(err) {
          commonlib.__INFO("Fail to get build node")
          commonlib.__INFO(err.toString())
          currentBuild.result = 'FAILURE'
          configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
          configRuntime.BUILD_STATUS = 'FAILURE'
        }

        if ( ! configRuntime.build_variant[build_target].least_loaded_node ){
          commonlib.__INFO("ERROR: Unable to get build node.")
          currentBuild.result = 'ABORTED'
          configRuntime.stage_results[STAGE_NAME] = "ABORTED"
          configRuntime.BUILD_STATUS = 'ABORTED'
          configRuntime.verify_score = 0
        } else {
          commonlib.__INFO("INFO: Best build node is: ${configRuntime.build_variant[build_target].least_loaded_node}")
        }

        // Creating workspace Preparation/Publish/CleanUp Executors
        parameter_list = [
            'LEAST_LOADED_NODE='+               configRuntime.build_variant[build_target].least_loaded_node,
            'PROJECT_BRANCH='+                  configRuntime.project_branch,
            'SOURCE_VOLUME_BASELINE='+          configRuntime.build_variant[build_target].source_volume_baseline,
            'SOURCE_VOLUME='+                   configRuntime.build_variant[build_target].source_volume,
            'BUILD_TOOL_URL='+                  body.script.buildtools_url,
            'BUILD_TOOL_BRANCH='+               body.script.buildtools_branch,
            'VERBOSE='+                         VERBOSE,
            'RETAIN_WORKSPACE='+                configRuntime.retain_workspace,
            'BUILD_TARGET='+                    build_target,
        ]
        if (configRuntime.get_flash_image_clean) {
          // Request clean build.
          parameter_list += [ 'PIPELINE_TYPE='+                   configRuntime.pipelineType + get_flash_image_msg_clean ]
        } else {
          // Incremental build
          parameter_list += [ 'PIPELINE_TYPE='+                   configRuntime.pipelineType]
        }
        aospPrepareWSParallelMap[build_target] = commonlib.configureWorkspace("prepare", parameter_list)
      }
    }
  }
  try {
    parallel aospPrepareWSParallelMap
  } catch (FlowInterruptedException e) {
    throw e
  } catch(err) {
    commonlib.__INFO(err.toString())
    currentBuild.result = 'FAILURE'
    configRuntime.stage_results[STAGE_NAME] = "FAILURE"
    configRuntime.BUILD_STATUS = 'FAILURE'
    configRuntime.build_addinfo = "(Create BTRFS Snapshot WS)!!!"
    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
  }
}
