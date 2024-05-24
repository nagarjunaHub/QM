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

  def aospTestParallelMap = [:]
  def aospDevelBuildSyncParallelMap = [:]
  def aospDevelFinalBuildSyncParallelMap = [:]

  def launched_jobs = [:]
  def devel_sync_parameter_list = [:]

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

          commonlib.onHandlingException("Getting Build Bot List"){
              // Get build variant IDs based on manifest release
              build_node_list = commonlib.getBuildNodes(stackTarget, stackTargetFile, VERBOSE)
          }

          if (! build_node_list){
              currentBuild.result = 'ABORTED'
              configRuntime.stage_results[STAGE_NAME] = 'ABORTED'
              commonlib.__INFO("No Build Bot Found for ${build_target} and ${configRuntime.project_branch} ==> SKIP Testing!!!")
              return
          }

          commonlib.__INFO("Build ${build_target} on ${configRuntime.build_variant[build_target].least_loaded_node}")

          if (build_type.contains("userdebug") && config[configRuntime.pipelineType][target_id]["qualification_tests"]){
              // Creating Testing Excutors
              def testing = config[configRuntime.pipelineType][target_id]["qualification_tests"]
              println "Qualification_tests for ${target_id}:" + testing.toString()
              for (def test_type in testing.keySet()) {
                if (testing[test_type]["test_job"] == "") {
                  commonlib.__INFO("Test Job name is not available for ${build_target} ==> SKIP Testing!!!")
                } else {
                  def job_name = testing[test_type]["test_job"]
                  def timeout = testing[test_type]["timeout"]
                  def wait_duration = testing[test_type]["wait"].toBoolean()
                  def propagate = testing[test_type]["propagate"].toBoolean()

                  def test_parameter_list = [
                    "ANDROID_IMAGE_PATH="+        configRuntime.NET_SHAREDRIVE_TABLE[configRuntime.pipelineType] + '/' + configRuntime.project_release_version +'/'+ lunch_target,
                    "CAUSED_BY="+                 [JOB_NAME,BUILD_NUMBER].join("/"),
                    "BUILDBOT_NODE="+             configRuntime.build_variant[build_target].least_loaded_node,
                    "DOCKER_IMAGE_ID="+           config.docker_image_id,
                    "SOURCE_VOLUME="+             configRuntime.build_variant[build_target].source_volume,
                    "LUNCH_TARGET="+              lunch_target,
                    "TRADEFED_PATH="+             "${env.USER}@${configRuntime.build_variant[build_target].least_loaded_node}:${configRuntime.build_variant[build_target].source_volume}/out/dist/eb-tradefed.zip",
                    'BUILD_TARGET='+              build_target,

                  ]

                  // Append all parameters from the config file
                  def params = testing[test_type]["params"]
                  for (def p in params.keySet()) {
                    test_parameter_list += [p + "=" + params[p]]
                  }

                  println "For ${build_target}: " + test_parameter_list.toString()
                  // An unique identifier to collect the test results later.
                  def test_identifier = build_target + "__" + test_type
                  aospTestParallelMap[test_identifier] = {
                    node(configRuntime.build_variant[build_target].least_loaded_node){
                        launched_jobs[test_identifier] = commonlib.eb_build( job: job_name, timeout: timeout, wait: wait_duration, propagate: propagate, parameters: test_parameter_list, configRuntime: configRuntime )
                        print("Results from testing: ")
                        print(launched_jobs[test_identifier])
                        if (launched_jobs[test_identifier].EXCEPT) {
                            throw launched_jobs[test_identifier].EXCEPT
                        }
                    }
                  }
                }
              }
          }
      }
    }
  }

  try {
        parallel aospTestParallelMap
  } catch (FlowInterruptedException e) {
      throw e
  } catch(err) {

    def failure_mail_body_builder = ""
    for (launched_job in launched_jobs.keySet()) {
      if(launched_jobs[launched_job]["RESULT"] != "SUCCESS") {
          // Unpack the test_identifier into build_target and test_type
          def (build_target, test_type) = launched_job.split("__")
          commonlib.__INFO("Error: An error occured during Qualification testing for ${build_target} marking build as FAILURE.")
          configRuntime.test_addinfo = configRuntime.test_addinfo + " ${build_target}"
          failure_mail_body_builder = failure_mail_body_builder+build_target+":"+launched_jobs[launched_job]["BUILD_URL"]+":"+configRuntime.TEST_STATUS+"\n\t"
          configRuntime.email_recipients = configRuntime.email_recipients + config.email_testing_team // email testing team only if testing fails.
      }
    }

    configRuntime.TEST_STATUS = "FAILURE"
    currentBuild.result = "FAILURE"
    configRuntime.stage_results[STAGE_NAME] = 'FAILURE'
    commonlib.__INFO(err.toString())

    configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),failure_mail_body_builder+"%s",configRuntime.email_build_info)

  }

}
