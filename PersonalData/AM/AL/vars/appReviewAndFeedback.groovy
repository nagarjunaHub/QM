import com.eb.lib.aosp.aospUtils
import com.eb.lib.aosp.CommonEnvironment
import groovy.json.JsonBuilder

def call(body) {
  // For pipeline common config
  def commonlib = new aospUtils()
  def config = body.script.config
  def configRuntime = body.script.configRuntime
  def changelist = ""

  node(configRuntime.leastloadednode) {
    def launchersDir = "${configRuntime.workspace}/.launchers"
      if (!fileExists(launchersDir)) {
          dir "${configRuntime.workspace}/.launchers", {
            git branch: "${body.script.buildtools_branch}",
                    url: "${body.script.buildtools_url}"
            }
        } else {
            echo "Directory ${configRuntime.workspace}/.launchers already exists."
      }
      try {
          timestamps {
              if (configRuntime.BUILD_STATUS == 'FAILURE'){
                  configRuntime.verify_message = configRuntime.verify_message + "\n\t [  ] BUILD: FAILED " + configRuntime.build_addinfo
                  configRuntime.verify_score = -1
              } else {
                  configRuntime.verify_message = configRuntime.verify_message + "\n\t [OK] BUILD: PASSED"
                  if (configRuntime.TEST_STATUS == 'FAILURE'){
                      configRuntime.verify_message = configRuntime.verify_message + "\n\t [  ] TEST: FAILED " + configRuntime.test_addinfo
                      if (configRuntime.disable_testing == false) {
                          configRuntime.verify_score = -1
                      }
                  } else {
                      if (configRuntime.disable_testing == true) {
                          configRuntime.verify_message = configRuntime.verify_message + "\n\t [ ] TEST: Test Disabled"
                      } else {
                          configRuntime.verify_message = configRuntime.verify_message + "\n\t [OK] TEST: PASSED"
                      }
                  }
              }

              configRuntime.verify_message = configRuntime.verify_message + "\n\n * Build Log: ${env.BUILD_URL}consoleFull"
              if (configRuntime.verify_score == 0){
                  configRuntime.verify_message = "App Jenkins job is not configured to run ${GERRIT_PROJECT}. Please contact CI team."
              }

              // the depends-on changes shouldn't be given -1 when verify fails.
              // But, if verify passes, +1 should be given. This is to avoid overwriting a previous verified +1 score
              // on the dependent changes if at all they were verified independently.
              if (configRuntime.verify_score > 0 && configRuntime.dependencies != "") {
                  // Join all the changes, # separated. This goes as an input to the promotion job, which calls a couple of python scripts that validates, and submit all changes, atomically.
                  changelist = configRuntime.dependencies.split(" ").join("%23")
                  def parameter_list = [
                      "CHANGELIST="+                changelist
                  ]

                  def launched_jobs = [:]
                  launched_jobs["ReviewFeedback"] = commonlib.eb_build( job: config.bundle_promotion_job,
                    timeout: 1800, wait: true, propagate: true, parameters: parameter_list )

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
          } /* timestamp */
      } catch (err) {
          commonlib.__INFO(err.toString())
          currentBuild.result = 'FAILURE'
          configRuntime.build_addinfo = "(Review Feedback)!!!"
          configRuntime.failure_mail_body = String.format(configRuntime.failure_mail_body.toString(),err.toString()+configRuntime.build_addinfo+"%s",configRuntime.email_build_info)
      } /* catch */
    }
}
