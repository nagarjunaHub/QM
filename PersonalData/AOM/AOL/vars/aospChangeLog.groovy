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

  Map aospChangeLogParallelMap = [:]
  if (config[configRuntime.pipelineType].parallel_failfast?.get(STAGE_NAME)?:false == 'true' ) {
    commonlib.__INFO("Parallel failfast is enabled for " + STAGE_NAME + " stage")
    aospChangeLogParallelMap.failFast = true
  }
  node(configRuntime.pipeline_node) {
      def build_type_list = ['userdebug']
      target_id_list.each { target_id ->
        build_type_list.each { build_type ->
            if ( config["supported_target_ids"][target_id][configRuntime.pipelineType] == "disabled" ) {
              commonlib.__INFO("WARNING: " + configRuntime.pipelineType + " is disabled for " + target_id + ". If you want, you can enable it in " + PROJECT_CONFIG_FILE)
              return
            }
            if (config.prebuilt_release_required == null) {
              configRuntime.prebuilt_release_name = "n/a"
            } else {
              lunch_target = String.format(config["supported_target_ids"][target_id]["lunch_target_template"], build_type)
              configRuntime.prebuilt_release_name = [config.project_line,config.project_type,config.android_version,config.branch_identifier,"prebuilt",configRuntime.pipelineType].join("_").trim().toUpperCase() + "/" + lunch_target
            }
            stackTarget = "StackTarget_${configRuntime.project_branch}_${target_id}_${build_type}"
            stackTargetFile = config.stacktarget_file
            build_target = [target_id,build_type].join("-")

            commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

            // Creating Change Log Excutors
            parameter_list = [
                'LEAST_LOADED_NODE='+         configRuntime.build_variant[build_target].least_loaded_node,
                'SOURCE_VOLUME='+             configRuntime.build_variant[build_target].source_volume,
                'PIPELINE_TYPE='+             configRuntime.pipelineType,
                'BUILD_TYPE='+                build_type,
                'TARGET_ID='+                 target_id,
                'PROJECT_RELEASE_VERSION='+   configRuntime.project_release_version,
                'PREBUILT_RELEASE_NAME='+     configRuntime.prebuilt_release_name,
                'REPO_MANIFEST_RELEASE='+     config.repo_release_manifest_url,
                'VERBOSE='+                   VERBOSE,
                'CHANGE_LOG_TYPES='+          config[configRuntime.pipelineType].changeLogTypes,
                'REPO_MANIFEST_LOG_BASE='+    configRuntime.repo_rel_manifest_revision,
                'BUILD_TARGET='+              build_target,
                'PROJECT_BRANCH='+            config.project_branch,
                'TAG_PREFIX='+                configRuntime.versionTemplateBase,
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
                    'REPO_MANIFEST_LOG_BASE='+    configRuntime.project_branch,
                    'REPO_MANIFEST_RELEASE_REVISION='+    configRuntime.project_branch
                ]
            }
            aospChangeLogParallelMap[build_target] = commonlib.configureChangeLog(parameter_list + add_params)
          }
        }
    try {
      parallel aospChangeLogParallelMap
    } catch (FlowInterruptedException e) {
        throw e
    } catch(err) {
      commonlib.__INFO(err.toString())
      currentBuild.result = 'FAILURE'
      configRuntime.CHANGELOG_STATUS = "FAILURE"
      configRuntime.stage_results[STAGE_NAME] = "FAILURE"
      configRuntime.build_addinfo = "(ChangeLog)!!!"
      configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
    }
  }
}
