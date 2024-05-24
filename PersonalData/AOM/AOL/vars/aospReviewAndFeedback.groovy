import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  def changelist = ""

  def target_id_list = configRuntime.supported_targets.tokenize(" ")

  node(configRuntime.pipeline_node) {
      try {
          timestamps {
              if(configRuntime.get_flash_image == true) {
                  if (configRuntime.BUILD_STATUS == 'FAILURE'){
                    configRuntime.verify_message = "Get Flash Image For Patchset ${GERRIT_PATCHSET_NUMBER} Is Failed: \n\t" + "${env.BUILD_URL}consoleFull"
                  } else if (configRuntime.BUILD_STATUS == 'ABORTED') {
                    configRuntime.verify_message = "Get Flash Image For Patchset ${GERRIT_PATCHSET_NUMBER} Is Aborted: \n\t" + "${env.BUILD_URL}consoleFull"
                  } else {
                    configRuntime.verify_message = "Get Flash Image For Patchset ${GERRIT_PATCHSET_NUMBER} Is Available Here (48 Hrs): \n\t" + configRuntime.NET_SHAREDRIVE_TABLE["get_flash"]  + configRuntime.get_flash_version
                  }
                  commonlib.gerrit_PostComment(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, configRuntime.verify_message)
              } else {
                  if (configRuntime.BUILD_STATUS == 'FAILURE'){
                      configRuntime.verify_message = configRuntime.verify_message + "\n\t [  ] BUILD: FAILED " + configRuntime.build_addinfo
                      configRuntime.verify_score = -1
                  } else if (configRuntime.BUILD_STATUS == 'ABORTED') {
                      configRuntime.verify_message = configRuntime.verify_message + "\n\t [  ] BUILD: ABORTED " + configRuntime.build_addinfo
                      configRuntime.verify_score = 0
                  } else {
                      configRuntime.verify_message = configRuntime.verify_message + "\n\t [OK] BUILD: PASSED"
                      if (configRuntime.TEST_STATUS == 'FAILURE'){
                          configRuntime.verify_message = configRuntime.verify_message + "\n\t [  ] TEST: FAILED " + configRuntime.test_addinfo
                          if (configRuntime.disable_testing == false) {
                              configRuntime.verify_score = -1
                          }
                      } else {
                              configRuntime.verify_message = configRuntime.verify_message + "\n\t [OK] TEST: PASSED"
                      }
                  }

                  if (configRuntime.verify_score < 0) {
                      configRuntime.verify_message = configRuntime.verify_message + "\n\n * SysInt verdict: FAILED"
                      configRuntime.verify_message = configRuntime.verify_message + "\n\n * Retrigger This Build: ${env.BUILD_URL}gerrit-trigger-retrigger-this"
                  } else {
                      if (currentBuild.result == "FAILURE") {
                        configRuntime.verify_message = configRuntime.verify_message + "\n\n * SysInt verdict: " + currentBuild.result
                        configRuntime.verify_score = -1
                      } else if (currentBuild.result == 'ABORTED') {
                        configRuntime.verify_message = configRuntime.verify_message + "\n\n * SysInt verdict: " + currentBuild.result
                        configRuntime.verify_score = 0
                      } else {
                        configRuntime.verify_message = configRuntime.verify_message + "\n\n * SysInt verdict: PASSED"
                      }

                  }
                  configRuntime.verify_message = configRuntime.verify_message + "\n\n * Build Log: ${env.BUILD_URL}consoleFull"
                  if (configRuntime.verify_score == 0){
                      configRuntime.verify_message = configRuntime.verify_message +
                          "\n\n * Build got aborted. Reason could be: " +
                          "\n\t 1. It was aborted manually." +
                          "\n\t 2. If this repo was just added to the AOSP-manifest, then please wait until the manifest change" +
                          "\n\t passes the devel pipeline, usually takes 2 hours after the change is merged." +
                          "\n\t 3. If this is not a new repo, then you probably uploaded your change to the wrong branch. To get " +
                          "\n\t the right development branch name, run the script 'device/*/*/get_branch_name.py' and upload " +
                          "\n\t your changes to this branch instead. If you still need help, contact CI team."
                  }

                  if (configRuntime.verify_score > 0 && configRuntime.dependencies != "") {
                      // Join all the changes, # separated. This goes as an input to the promotion job, which calls a couple of python scripts that validates, and submit all changes, atomically.
                      changelist = configRuntime.dependencies.split(" ").join("#")
                      def parameter_list = [
                          "CHANGELIST="+                changelist
                      ]

                      def launched_jobs = [:]
                      launched_jobs["ReviewFeedback"] = commonlib.eb_build( job: config.bundle_promotion_job,
                        timeout: 1800, wait: true, propagate: true, parameters: parameter_list, configRuntime: configRuntime )

                      if (launched_jobs["ReviewFeedback"].EXCEPT) {
                          throw launched_jobs[build_target].EXCEPT
                      }

                      if (configRuntime.dependencies.contains(",false")) {
                        configRuntime.verify_message = "\n\n * Bundled changes: \n\tThis is a bundle-verify. One or more of the commits in Relation Chain are based on outdated patchset." + \
                        "\n" + "This makes submitting all changes to fail sometimes. So please go through the list of changes below and " + \
                        "\n" + "those changes numbers beside which you see the string 'patchset-out-of-date' are based on an outdated patchset. Fix them by rebasing, and then run verify again." + \
                        "\n" + " This is the reason why your change has been given a Verified -1.\n\n" + configRuntime.verify_message
                        configRuntime.verify_score = -1
                      } else {
                        configRuntime.verify_message =  "\n\n * Bundled changes: \n\t Get Code-Review +2 for " + configRuntime.dependencies.replace(",true", "") + \
                        " then go to " + launched_jobs["ReviewFeedback"].BUILD_URL + "promotion/ and click on Execute Promotion. This will submit all bundled changes, provided they have right score.\n\n" + \
                        configRuntime.verify_message
                        // Verified +1 will be given by bundle promotion job!
                      }
                      commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, configRuntime.verify_message.toString(), configRuntime.verify_score)

                  } else {
                   // There is only one change - i.e not a bundle. So, give it a verified +1 and let the devs submit it whenever they want.
                    commonlib.gerrit_SetReview(GERRIT_HOST, GERRIT_CHANGE_NUMBER, GERRIT_PATCHSET_NUMBER, configRuntime.verify_message.toString(), configRuntime.verify_score)
                  }
              }

          } /* timestamp */
      } catch (FlowInterruptedException e) {
          throw e
      } catch (err) {
          commonlib.__INFO(err.toString())
          currentBuild.result = 'FAILURE'
          configRuntime.stage_results[STAGE_NAME] = "FAILURE"
          configRuntime.build_addinfo = "(Review Feedback)!!!"
          configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      } /* catch */
    }
  }
