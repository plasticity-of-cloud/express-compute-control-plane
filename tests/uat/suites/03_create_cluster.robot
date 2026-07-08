*** Settings ***
Documentation    Tests for 'eks-dx create-cluster':
...              - Name validation (no server required)
...              - Duplicate name → 409 error message
...              - Happy path managed (mock 202)
...              - Happy path self-managed (mock 201)
...              - --output json
...              - Self-managed flag validation

Library          ../libraries/MockServerLibrary.py
Resource         ../resources/common.resource

Suite Setup      Start Suite
Suite Teardown   Stop Mock Server


*** Variables ***
${PORT}    ${EMPTY}


*** Keywords ***
Start Suite
    ${p}=    Start Mock Server
    Set Suite Variable    ${PORT}    ${p}

Register Managed 202
    [Arguments]    ${cluster_name}=new-cluster
    Set Response    POST    /clusters    202
    ...    {"tenantId":"abc12345","clusterName":"${cluster_name}","managed":true}

Register Self Managed 201
    [Arguments]    ${cluster_name}=my-k3s
    Set Response    POST    /clusters    201
    ...    {"tenantId":"def67890","clusterName":"${cluster_name}","managed":false}

Register 409
    [Arguments]    ${cluster_name}=existing-cluster
    Set Response    POST    /clusters    409
    ...    {"__type":"ResourceInUseException","message":"Cluster '${cluster_name}' already exists. To replace it, run: eks-dx delete-cluster ${cluster_name}"}

Register 429
    Set Response    POST    /clusters    429
    ...    {"__type":"QuotaExceededException","message":"Quota exceeded: maximum 1 cluster(s) per caller"}

Register 500
    Set Response    POST    /clusters    500
    ...    {"__type":"InternalServerException","message":"Internal server error"}


*** Test Cases ***

# ---------------------------------------------------------------------------
# Name validation — no server call needed
# ---------------------------------------------------------------------------

Missing Cluster Name Exits Nonzero
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster
    Should Fail    ${r}

Name Starting With Digit Is Rejected
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    1invalid
    Should Fail    ${r}

Name With Underscore Is Rejected
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    bad_name
    Should Fail    ${r}

Name With Only Spaces Is Rejected
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    ${SPACE}
    Should Fail    ${r}

# ---------------------------------------------------------------------------
# Managed mode — mock server responses
# ---------------------------------------------------------------------------

Happy Path Managed Returns Success Message
    [Setup]    Reset Mock Server
    Register Managed 202    new-cluster
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    new-cluster
    Should Succeed    ${r}
    Stdout Should Contain    ${r}    new-cluster
    Request Was Made    POST    /clusters

Happy Path Managed Output JSON
    [Setup]    Reset Mock Server
    Register Managed 202    json-cluster
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    json-cluster    --output    json
    Should Succeed    ${r}
    ${parsed}=    Stdout Should Be Valid JSON    ${r}
    Dictionary Should Contain Key    ${parsed}    tenantId

Duplicate Name Returns 409 With Actionable Message
    [Setup]    Reset Mock Server
    Register 409    existing-cluster
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    existing-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    existing-cluster
    Stderr Should Contain    ${r}    delete-cluster

Quota Exceeded Returns 429 Message
    [Setup]    Reset Mock Server
    Register 429
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    any-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    Quota exceeded

Server Error Returns Helpful Message
    [Setup]    Reset Mock Server
    Register 500
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    any-cluster
    Should Fail    ${r}
    Stderr Should Contain    ${r}    Internal server error

# ---------------------------------------------------------------------------
# Self-managed mode — flag validation (no server call for invalid combos)
# ---------------------------------------------------------------------------

Self Managed Missing Issuer Exits Nonzero
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    my-k3s
    ...    --jwks-uri    https://k3s.example.com/.well-known/openid/v1/jwks
    Should Fail    ${r}

Self Managed Missing JWKS Exits Nonzero
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    my-k3s
    ...    --issuer    https://k3s.example.com
    Should Fail    ${r}

Self Managed Happy Path With JWKS File
    [Setup]    Reset Mock Server
    Register Self Managed 201    my-k3s
    # Write a minimal JWKS file
    ${jwks_file}=    Set Variable    /tmp/uat-test-jwks.json
    Create File    ${jwks_file}    {"keys":[{"kty":"RSA","n":"test","e":"AQAB"}]}
    ${r}=    Run CLI With Mock    ${PORT}    create-cluster    my-k3s
    ...    --jwks-file    ${jwks_file}
    ...    --issuer    https://k3s.example.com
    Should Succeed    ${r}
    Request Was Made    POST    /clusters
    ${body}=    Get Last Request Body    POST    /clusters
    Should Contain    ${body}    my-k3s
    Should Contain    ${body}    https://k3s.example.com
