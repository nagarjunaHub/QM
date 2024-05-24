import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime

  def launched_jobs = [:]
  def test_parameter_list = [:]
  def project_type = ""

  if (configRuntime.BUILD_STATUS == "SUCCESS") {
    if(configRuntime.disable_testing == false) {
      node(configRuntime.leastloadednode) {
        timestamps {
          def launchersDir = "${configRuntime.workspace}/.launchers"
          if (!fileExists(launchersDir)) {
            dir "${configRuntime.workspace}/.launchers", {
              git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
            }
          } else {
            echo "Directory ${configRuntime.workspace}/.launchers already exists."
          }
        }
        if (configRuntime.deploy_to_git == 'true' ){
          project_type = "apk"
        }
        else if (configRuntime.deploy_to_artifactory == 'true' ) {
          project_type = "aar"
        } else {
          project_type = ""
        }
        try {
          echo "Test job has been started: ${configRuntime.test_job}"
          test_parameter_list = [
                  "BUILDBOT_WORKSPACE=" + configRuntime.leastloadednode + ":" + configRuntime.build_workspace,
                  "APP_ROOT_DIR=" + configRuntime.project,
                  "DEVENV=" + config.dev_env,
                  "VERBOSE=" + VERBOSE,
                  "CAUSED_BY=" + [JOB_NAME,BUILD_NUMBER].join("/"),
                  "TESTING_STAGE=" + config[configRuntime.pipelineType]["TESTING_STAGE"],
                  "PROJECT_TYPE=" + project_type
          ]
          launched_jobs = commonlib.eb_build(job: configRuntime.test_job, wait: true, propagate: true, timeout: configRuntime.test_job_timeout, parameters: test_parameter_list)
          configRuntime.TEST_URL = launched_jobs.BUILD_URL
          print("Results from testing: ${launched_jobs}")
          if (launched_jobs.EXCEPT) {
            throw launched_jobs.EXCEPT
          }
        } catch(err) {
          def failure_mail_body_builder = ""
          if (launched_jobs.RESULT != "SUCCESS") {
            commonlib.__INFO("Error: An error occured during testing, marking build as FAILURE.")
            configRuntime.test_addinfo = "(App Test)!!!"
            failure_mail_body_builder = failure_mail_body_builder + configRuntime.TEST_URL + ":" + launched_jobs.RESULT + "\n\t"
            configRuntime.email_recipients = configRuntime.email_recipients + ',' + config.email_testing_team // email testing team only if testing fails.
          }
          configRuntime.TEST_STATUS = 'FAILURE'
          currentBuild.result = "FAILURE"
          commonlib.__INFO(err.toString())
          configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),failure_mail_body_builder+"%s",configRuntime.email_build_info)
        }
      }
    } else {
      commonlib.__INFO("Testing is disabled for ${GERRIT_PROJECT}")
    }
  }
}
