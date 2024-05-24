import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config

  def configRuntime = body.script.configRuntime
  def target_id_list = configRuntime.affected_manifests.collect { it.replaceAll('.xml', '') }

  def notifyIntegrationCompletionInGerrit = [:]

  node(configRuntime.pipeline_node) {

      target_id_list.each { target_id ->
        def build_type_list = ['userdebug']
        build_type_list.each { build_type ->
        if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
          commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
          return
        }

        stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
        stackTargetFile = config.stacktarget_file
        build_target = [target_id,build_type].join("-")

        // Restrict to using BUILD_ON_NODE if supplied.

        if (build_type.contains("userdebug") && ! configRuntime.pipelineType.contains("verify") && config.notify_integration_completion_in_gerrit == "enabled") {
          def params_notifyIntegrationCompletion = [
            'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
            'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
            'BUILD_NAME='+                configRuntime.project_release_version,
            'PIPELINE_TYPE='+             configRuntime.pipelineType,
            'TARGET_ID='+                 target_id,
            'REPO_MANIFEST_URL='+         config.repo_release_manifest_url,
            'GERRIT_HOST='+               config.gerrit_host,
            'BUILD_TOOL_URL='+            body.script.buildtools_url,
            'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
            'VERBOSE='+                   VERBOSE,
            'NET_SHAREDRIVE='+            configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType],
            'BUILD_TARGET='+              build_target,
            'PROJECT_BRANCH='+            config.project_branch,
          ]

          if (configRuntime.pipelineType.contains("devel")) {
            params_notifyIntegrationCompletion += ['REPO_MANIFEST_REVISION='+    configRuntime.pipeline_tag,]
          }

          if (configRuntime.pipelineType.contains("snapshot")) {
            params_notifyIntegrationCompletion += ['REPO_MANIFEST_REVISION='+    configRuntime.repo_rel_manifest_revision,]
          }
          if ( configRuntime.project_branch =~ 'rel'){
            params_notifyIntegrationCompletion += ['REPO_MANIFEST_REVISION='+    configRuntime.project_branch,]
          }

          notifyIntegrationCompletionInGerrit[build_target] = commonlib.configureNotifyIntegrationCompletionInGerrit(params_notifyIntegrationCompletion)
            }

        }
      }

    }

  try {
    parallel notifyIntegrationCompletionInGerrit
  } catch (FlowInterruptedException e) {
      throw e
  } catch(err) {
    configRuntime.stage_results[STAGE_NAME] = "FAILURE"
    commonlib.__INFO(err.toString())
    commonlib.__INFO("There was an error writing snapshot information to gerrit changes integrated since last snapshot.")
  }

}
