# This repo branch serves AOSP and Apps

## AOSP

1. pipeline/T2K_PIPELINE.groovy is the main pipeline script that's used in t2k, asteirx2, and skilfish!<br>
Bear with the name, please.<br>
Please find time and change the file name to something generic and update all AOSP Jenkins jobs accordingly.

2. This script uses the shared-lib from the repo
https://t2k-gerrit.elektrobit.com/plugins/gitiles/infrastructure/pipeline-global-library/+log/refs/heads/aosp<br>
Branch: aosp

3. So, pipeline/T2K_PIPELINE.groovy is some sort of skeleton, and the actual functions are all defined in the shared-lib.

4. bash libraries (file extension .lib) in this repo are used by aosp pipeline.
You will find them in libtools/ These functions are called by the shared-lib!

## APPS

1. pipeline/T2K_APP_PIPELINE.groovy is the main pipeline.

2. Uses the bashlib functions, and common.groovy 
