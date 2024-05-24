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

  Map aospSyncParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospSyncParallelMap.failFast = true
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

          commonlib.__INFO("Build aospSyncParallelMap ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

          // Creating workspace repo sync Executors
          parameter_list = [
              'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
              'PIPELINE_TYPE='+             configRuntime.pipelineType,
              'TARGET_ID='+                 target_id,
              'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
              'DEV_NULL='+                  config.dev_null,
              'BUILD_TOOL_URL='+            body.script.buildtools_url,
              'BUILD_TOOL_BRANCH='+         body.script.buildtools_branch,
              'VERBOSE='+                   VERBOSE,
              'REPO_MANIFEST_XML='+         config["supported_target_ids"][target_id]["repo_manifest_xml"],
              'FRESH_REPO_WORKSPACE='+      configRuntime.fresh_repo_workspace,
              'BUILD_TARGET='+              build_target,
          ]
          def add_params = [
              'REPO_MANIFEST_URL='+         config.repo_release_manifest_url,
              'REPO_MANIFEST_REVISION='+    configRuntime.repo_dev_manifest_revision,
          ]

          if (configRuntime.pipelineType.contains("verify")){
              add_params += [
                  'GERRIT_HOST='+             GERRIT_HOST,
                  'GERRIT_PROJECT='+          GERRIT_PROJECT,
                  'GERRIT_CHANGE_NUMBER='+    GERRIT_CHANGE_NUMBER,
                  'GERRIT_PATCHSET_NUMBER='+  GERRIT_PATCHSET_NUMBER,
                  'DEPENDENCIES='+            configRuntime.dependencies
              ]
          } else if (configRuntime.pipelineType.contains("snapshot")) {
            def checkout_ref = sh(script: "git ls-remote --tags ${config.repo_release_manifest_url} ${configRuntime.devel_tag} | awk '{print \$1}'", returnStdout: true).trim()
            // if (checkout_ref == "") then use the default branch
            if (checkout_ref == "") {
              checkout_ref = configRuntime.repo_dev_manifest_revision
            }
              add_params = [
                  'REPO_MANIFEST_URL='+       config.repo_release_manifest_url,
                  'REPO_MANIFEST_REVISION='+  checkout_ref
              ]
          }
          if ( configRuntime.project_branch =~ 'rel'){
            add_params = [
                  'REPO_MANIFEST_URL='+       config.repo_release_manifest_url,
                  'REPO_MANIFEST_REVISION='+  configRuntime.project_branch
              ]
          }
          aospSyncParallelMap[build_target] = commonlib.configureSync(parameter_list + add_params)
      }
    }
  }
  try {
    parallel aospSyncParallelMap
  } catch (FlowInterruptedException e) {
      throw e
  } catch(err) {
    configRuntime.build_addinfo = "(Repo Sync)"
    commonlib.__INFO(err.toString())
    currentBuild.result = 'FAILURE'
    configRuntime.stage_results[STAGE_NAME] = "FAILURE"
    configRuntime.BUILD_STATUS = 'FAILURE'
    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
  }

}
