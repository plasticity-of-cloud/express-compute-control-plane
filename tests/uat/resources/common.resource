*** Settings ***
Documentation    Common variables and keywords shared across all UAT suites.

Library    Process
Library    OperatingSystem
Library    String
Library    Collections
Library    json


*** Variables ***
# CLI_JAR is set by run-uat.sh; override with --variable CLI_JAR:<path>
${CLI_JAR}          ${EMPTY}
${CLI_BINARY}       ${EMPTY}
${HOME_DIR}         %{HOME}
${CONFIG_DIR}       ${HOME_DIR}/.eks-d-xpress


*** Keywords ***
Run CLI
    [Documentation]    Run the eks-dx CLI with the given arguments and environment.
    ...                Returns the result object (rc, stdout, stderr).
    [Arguments]    @{args}    &{env}
    ${java_cmd}=    Set Variable If
    ...    '${CLI_BINARY}' != '${EMPTY}'    ${CLI_BINARY}
    ...    java -jar ${CLI_JAR}
    # Split java -jar <path> into a list so Process handles it correctly
    ${cmd_parts}=    Run Keyword If
    ...    '${CLI_BINARY}' != '${EMPTY}'
    ...    Create List    ${CLI_BINARY}    @{args}
    ...    ELSE
    ...    Create List    java    -jar    ${CLI_JAR}    @{args}
    ${result}=    Run Process    @{cmd_parts}    env:HOME=${HOME_DIR}    &{env}
    RETURN    ${result}

Run CLI With Mock
    [Documentation]    Run CLI pointing both management and provisioning URLs at the mock server.
    [Arguments]    ${port}    @{args}    &{extra_env}
    ${mock_url}=    Set Variable    http://127.0.0.1:${port}
    ${result}=    Run CLI
    ...    @{args}
    ...    env:EKS_DX_ENDPOINT=${mock_url}
    ...    env:EKS_DX_PROVISIONING_URL=${mock_url}
    ...    env:EKS_DX_STREAM_URL=${mock_url}
    ...    env:AWS_REGION=us-east-1
    ...    &{extra_env}
    RETURN    ${result}

Should Exit With Code
    [Arguments]    ${result}    ${expected_rc}
    Should Be Equal As Integers    ${result.rc}    ${expected_rc}
    ...    msg=Expected exit code ${expected_rc} but got ${result.rc}.\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}

Should Succeed
    [Arguments]    ${result}
    Should Exit With Code    ${result}    0

Should Fail
    [Arguments]    ${result}
    Run Keyword Unless    ${result.rc} != 0
    ...    Fail    Expected non-zero exit code.\nSTDOUT: ${result.stdout}\nSTDERR: ${result.stderr}

Stderr Should Contain
    [Arguments]    ${result}    ${expected}
    Should Contain    ${result.stderr}    ${expected}
    ...    msg=Expected stderr to contain '${expected}'.\nActual stderr: ${result.stderr}

Stdout Should Contain
    [Arguments]    ${result}    ${expected}
    Should Contain    ${result.stdout}    ${expected}
    ...    msg=Expected stdout to contain '${expected}'.\nActual stdout: ${result.stdout}

Stdout Should Be Valid JSON
    [Arguments]    ${result}
    ${parsed}=    Evaluate    __import__('json').loads('''${result.stdout}''')
    RETURN    ${parsed}
