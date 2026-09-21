Feature: Gmail INBOX watch
  Gmail publishes INBOX changes to the shared Pub/Sub topic only while a
  watch is alive, and a watch lives at most seven days. The watch is an
  entry on isaac-google's registration timer, like a Chat space
  subscription: created for the configured mailbox, renewed inside the
  renew window, stopped when the mailbox leaves config. Gmail cannot list
  watches, so the timer trusts what it remembered. Bean: isaac-12iz.

  Background:
    Given default Grover setup in "/test/gmail-watch"
    And config:
      | log.output                        | memory                         |
      | google.tonotop.topic              | projects/marigold/topics/isaac |
      | google.tonotop.renew-within-hours | 24                             |
      | comms.gmail.gmail/account         | yopp@tonotop.com               |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the clock is fixed at "2026-09-18T12:00:00Z"

  Scenario: the first tick watches INBOX for the configured mailbox and seeds the history cursor
    Given the Gmail API grants a watch with history id "900" expiring at "2026-09-25T12:00:00Z"
    When the Gmail watch timer ticks
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" matches:
      | method                 | POST                           |
      | headers.Authorization  | Bearer at-1                    |
      | body.topicName         | projects/marigold/topics/isaac |
      | body.labelIds.0        | INBOX                          |
      | body.labelFilterAction | include                        |
    And the log has entries matching:
      | level | event              | key              | expires-at           |
      | :info | :google/registered | yopp@tonotop.com | 2026-09-25T12:00:00Z |
    And the gmail history cursor is "900"

  Scenario: a watch outside the renew window is left alone
    Given the registration timer remembers a watch for "yopp@tonotop.com" expiring at "2026-09-24T12:00:00Z"
    When the Gmail watch timer ticks
    Then no outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" was made

  Scenario: a watch inside the renew window is watched again and the new expiry read back
    Given the registration timer remembers a watch for "yopp@tonotop.com" expiring at "2026-09-19T06:00:00Z"
    And the Gmail API grants a watch with history id "1500" expiring at "2026-09-25T12:00:00Z"
    When the Gmail watch timer ticks
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" matches:
      | method | POST |
    And the log has entries matching:
      | level | event           | key              | expires-at           |
      | :info | :google/renewed | yopp@tonotop.com | 2026-09-25T12:00:00Z |

  Scenario: a mailbox removed from config has its watch stopped
    Given the registration timer remembers a watch for "yopp@tonotop.com" expiring at "2026-09-24T12:00:00Z"
    And config:
      | comms.gmail.gmail/account | #delete |
    When the Gmail watch timer ticks
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/stop" matches:
      | method | POST |
    And the log has entries matching:
      | level | event                | key              |
      | :info | :google/unregistered | yopp@tonotop.com |

  Scenario: a refused watch is logged with Gmail's reason and tried again next tick
    Given the Gmail API refuses the watch with 403 "Insufficient Permission"
    When the Gmail watch timer ticks
    Then the log has entries matching:
      | level  | event                       | key              | reason                       |
      | :error | :google/registration-failed | yopp@tonotop.com | #".*Insufficient Permission.*" |
    Given the Gmail API grants a watch with history id "900" expiring at "2026-09-25T12:00:00Z"
    When the Gmail watch timer ticks
    Then the log has entries matching:
      | level | event              | key              |
      | :info | :google/registered | yopp@tonotop.com |
