#! /usr/bin/env python
""" This script will check a certain gerrit change if it is 'submittable.
    As we use this only for bundle verification jobs, we ignore the "Verified"
    setting, as this is set immediately before
    submitting the change, to prevent users from manually accepting changes
    in the Gerrit WebUI.
"""


import sys
import subprocess
import json
from optparse import OptionParser

def check_gerrit_change(change, patchset, verbose, gerrit_host):
  """ This function checks if all Gerrit lables are set properly
      (i.e. Code-Review +2)."""
  exitCode = 0
  json_out = None
  not_needed_labels = ['Verified']  # ignore verified, as we set this immediately before submitting.

  # reads project revion from revs_array
  cmd = "ssh -p 29418 {} gerrit query {} --submit-records --current-patch-set --format json".format(gerrit_host, change)

  if verbose:
    print("exec: {}".format(cmd))

  retVal = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE).stdout
  for line in retVal:
    if "project" in line.decode("utf-8"):
      json_out = json.loads(line.decode("utf-8"))

      # check if latest change
      item_patchset = json_out['currentPatchSet']['number']

      if item_patchset != patchset:
        print("Wrong patchset, cannot continue. \nCurrent Patchset is: {} Bundle was for: {}".format(item_patchset, patchset))
        #return the sys.exit(1)
        exitCode = 1

      # check submitRecords
      for item in json_out['submitRecords']:
        for label in item.get('labels'):
          if verbose:
            print(label)
          if label.get('label') not in not_needed_labels:
            if label.get('status') != "OK":
              print("Accept criteria not met. Label {} is not sufficient.".format(label.get('label')))
              #return the sys.exit(1)
              exitCode = 1

  if json_out == None:
    print("Gerrit change {} not found, cannot continue.".format(change))
    #return the sys.exit(1)
    exitCode = 1
  return exitCode

def check_gerrit_change_isMergeable(change, verbose, gerrit_host):
  """ This function checks if changes have no merge conflicts"""
  exitCode = 0
  cmd = "ssh -p 29418 {} gerrit query is:mergeable status:open | grep \"number: {}\"".format(gerrit_host, change)
  retVal = subprocess.call(cmd, shell=True)
  if verbose:
    print("exec: {}".format(cmd))
  if retVal == 1:
    print("Rebase required for the change: {}".format(change))
    #return the sys.exit(1)
    exitCode = 1
  return exitCode

def main():
  """ Main function"""

  # reading parameters:
  parser = OptionParser(sys.argv[0] + " -c CHANGE_NUMBER -p PATCHSET_NUMBER [-v|--verbose] \n")
  parser.add_option("-c", "", dest="change",
                    help="Gerrit change Number, i.e. 18123 ")
  parser.add_option("-p", "", dest="patchset",
                    help="Gerrit Patchset Number, i.e. 2 ")
  parser.add_option("-g", "--gerrit_host", dest="gerrit_host",
                    default="gerrit-ulm", help="gerrit host name")
  parser.add_option("-v", "--verbose", action="store_true", dest="verbose",
                    default=False, help="display status messages.")
  (params, args) = parser.parse_args()

  if params.change is None:
    parser.error("CHANGE_NUMBER is mandatory. ")

  if params.patchset is None:
    parser.error("PATCHSET_NUMBER is mandatory. ")

  # check gerrit change
  exitCode1 = check_gerrit_change(int(params.change), int(params.patchset), params.verbose, params.gerrit_host)
  exitCode2 = check_gerrit_change_isMergeable(params.change, params.verbose, params.gerrit_host)
  if exitCode1 == 1 or exitCode2 == 1:
    sys.exit(1)

if __name__ == '__main__':
  main()
