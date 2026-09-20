Feature: Gmail across several Google organizations
  One Isaac can carry several Google organizations (isaac-1zkz). A Gmail comm
  names the one it speaks for with `google`: it sends as that organization's
  Google user, with that organization's token, and its mailbox is watched
  against that organization's own topic. A host with one organization names
  none and nothing about it changes. Bean: isaac-1zkz.

  Background:
    Given default Grover setup in "/test/gmail-tenants"
    And config:
      | log.output                     | memory                          |
      | google.tonotop.project         | marigold                        |
      | google.tonotop.topic           | projects/marigold/topics/isaac  |
      | google.acme.project            | acme-prod                       |
      | google.acme.topic              | projects/acme-prod/topics/isaac |
      | comms.gmail.google             | tonotop                         |
      | comms.gmail.gmail/account      | yopp@tonotop.com                |
      | comms.gmail-acme.type          | gmail                           |
      | comms.gmail-acme.google        | acme                            |
      | comms.gmail-acme.gmail/account | isaac@acme.example              |
    And the google auth store for organization "tonotop" has access "at-tonotop" and refresh "rt-tonotop"
    And the google auth store for organization "acme" has access "at-acme" and refresh "rt-acme"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: a comm bound to an organization sends with that organization's token
    Given gmail comm "gmail-acme" is registered
    When gmail comm send! is invoked with:
      | path      | value              |
      | from      | ada@acme.example   |
      | subject   | Deploy?            |
      | thread-id | t-acme-1           |
      | content   | Shipping at noon.  |
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" matches:
      | method                | POST           |
      | headers.Authorization | Bearer at-acme |
      | body.threadId         | t-acme-1       |
    Given gmail comm "gmail" is registered
    When gmail comm send! is invoked with:
      | path      | value              |
      | from      | ada@tonotop.com    |
      | subject   | Standup            |
      | thread-id | t-tonotop-1        |
      | content   | On my way.         |
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" matches:
      | #index                | 1                 |
      | method                | POST              |
      | headers.Authorization | Bearer at-tonotop |
      | body.threadId         | t-tonotop-1       |

  Scenario: each mailbox is watched against its own organization's topic
    Given the Gmail API grants a watch with history id "900" expiring at "2026-09-25T12:00:00Z"
    When the Gmail watch timer ticks
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" matches:
      | #index                | 0                               |
      | method                | POST                            |
      | headers.Authorization | Bearer at-acme                  |
      | body.topicName        | projects/acme-prod/topics/isaac |
    And an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" matches:
      | #index                | 1                              |
      | method                | POST                           |
      | headers.Authorization | Bearer at-tonotop              |
      | body.topicName        | projects/marigold/topics/isaac |
    And the log has entries matching:
      | level | event              | key                |
      | :info | :google/registered | isaac@acme.example |
      | :info | :google/registered | yopp@tonotop.com   |
