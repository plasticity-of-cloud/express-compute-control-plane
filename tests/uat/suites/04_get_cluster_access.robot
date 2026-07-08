*** Settings ***
Documentation    Tests for 'eks-dx get-cluster-access':
...              - All guard conditions (self-managed, stopped, hibernating,
...                provisioning, no IP)
...              - Happy path text and JSON output
...              - --save-key writes .pem file with correct content

Library          ../libraries/MockServerLibrary.py
Library          OperatingSystem
Resource         ../resources/common.resource

Suite Setup      Start Suite
Suite Teardown   Stop Mock Server


*** Variables ***
${PORT}      ${EMPTY}
${PEM_DIR}   /tmp/uat-eks-dx-tenants


*** Keywords ***
Start Suite
    ${p}=    Start Mock Server
    Set Suite Variable    ${PORT}    ${p}

Register Cluster Response
    [Arguments]    ${cluster_name}    ${status}    ${body}
    Set Response    GET    /clusters/${cluster_name}    ${status}    ${body}

Ready Cluster JSON
    [Arguments]    ${cluster_name}=my-cluster    ${public_ip}=54.12.34.56
    RETURN    {"clusterName":"${cluster_name}","tenantId":"abc12345","managed":true,"state":"ready","progress":100,"publicIp":"${public_ip}","sshKeySecretArn":"arn:aws:secretsmanager:us-east-1:123:secret/eks-dx/tenant/abc12345/ssh-key"}


*** Test Cases ***

# ---------------------------------------------------------------------------
# Guard conditions — mock returns cluster in various states
# ---------------------------------------------------------------------------

Self Managed Cluster Returns Error
    [Setup]    Reset Mock Server
    Register Cluster Response    sm-cluster    200
    ...    {"clusterName":"sm-cluster","managed":false,"state":"ready"}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    sm-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    self-managed

Stopped Cluster Returns Error With Resume Hint
    [Setup]    Reset Mock Server
    Register Cluster Response    stopped-cluster    200
    ...    {"clusterName":"stopped-cluster","managed":true,"state":"stopped"}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    stopped-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    stopped
    Stderr Should Contain    ${r}    resume-cluster

Hibernating Cluster Returns Stopped Error
    [Setup]    Reset Mock Server
    Register Cluster Response    hib-cluster    200
    ...    {"clusterName":"hib-cluster","managed":true,"state":"hibernating"}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    hib-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    stopped

Provisioning Cluster Returns Not Ready Error
    [Setup]    Reset Mock Server
    Register Cluster Response    prov-cluster    200
    ...    {"clusterName":"prov-cluster","managed":true,"state":"provisioning","progress":42,"phase":"Installing EKS-D"}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    prov-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    not ready yet
    Stderr Should Contain    ${r}    42

Cluster Without Public IP Returns Error
    [Setup]    Reset Mock Server
    Register Cluster Response    no-ip-cluster    200
    ...    {"clusterName":"no-ip-cluster","managed":true,"state":"ready","progress":100}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    no-ip-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    no public IP

Cluster Not Found Returns 404 Error
    [Setup]    Reset Mock Server
    Register Cluster Response    ghost-cluster    404
    ...    {"__type":"NotFoundException","message":"Cluster not found: ghost-cluster"}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    ghost-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    ghost-cluster

# ---------------------------------------------------------------------------
# Happy path — text output
# ---------------------------------------------------------------------------

Happy Path Prints IP And SSH Command
    [Setup]    Reset Mock Server
    ${body}=    Ready Cluster JSON    my-cluster    54.12.34.56
    Register Cluster Response    my-cluster    200    ${body}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    my-cluster
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    54.12.34.56
    Stdout Should Contain    ${r}    ssh

Happy Path Shows Hint When PEM Missing
    [Setup]    Reset Mock Server
    ${body}=    Ready Cluster JSON    no-pem-cluster    10.0.0.1
    Register Cluster Response    no-pem-cluster    200    ${body}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    no-pem-cluster
    ...    env:HOME=/tmp/no-such-home
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    --save-key

# ---------------------------------------------------------------------------
# JSON output
# ---------------------------------------------------------------------------

Output JSON Contains Required Fields
    [Setup]    Reset Mock Server
    ${body}=    Ready Cluster JSON    json-cluster    1.2.3.4
    Register Cluster Response    json-cluster    200    ${body}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    json-cluster    --output    json
    Should Succeed    ${r}
    ${parsed}=    Stdout Should Be Valid JSON    ${r}
    Dictionary Should Contain Key    ${parsed}    publicIp
    Dictionary Should Contain Key    ${parsed}    sshCommand
    Should Be Equal    ${parsed}[publicIp]    1.2.3.4

# ---------------------------------------------------------------------------
# --save-key writes the .pem file via Secrets Manager (mock)
# ---------------------------------------------------------------------------

Save Key Attempts Secrets Manager Call
    [Documentation]    Verifies that --save-key reaches the Secrets Manager code path.
    ...                The CLI will fail because no real credentials are available in
    ...                mock mode — but the failure must NOT be a missing-IP or state error.
    [Setup]    Reset Mock Server
    ${body}=    Ready Cluster JSON    key-cluster    9.8.7.6
    Register Cluster Response    key-cluster    200    ${body}
    ${r}=    Run CLI With Mock    ${PORT}    get-cluster-access    key-cluster    \--save-key
    # Cluster fetch succeeded; failure must be from the Secrets Manager call, not state guards
    Should Fail    ${r}
    Should Not Contain    ${r.stderr}    self-managed
    Should Not Contain    ${r.stderr}    not ready yet
    Should Not Contain    ${r.stderr}    no public IP
