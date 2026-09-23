Feature: Gmail pull mode
  On hosts with no Pub/Sub push, gmail/mode :pull schedules an interval task
  on the shared scheduler that walks history from the stored cursor the same
  way the watch handler does — same gating, routes, and labels. No watch is
  registered, no Pub/Sub topic, no door required; login alone suffices. A
  first tick with no stored cursor seeds it from the newest history id and
  backfills nothing. A failed tick logs a warning and never fails the module;
  the next tick retries. Bean: isaac-u80t.

  Background:
    Given default Grover setup in "/test/gmail-pull"
    And config:
      | log.output                | memory           |
      | google.tonotop.project    | marigold         |
      | comms.gmail.gmail/account | yopp@tonotop.com |
      | comms.gmail.gmail/crew    | main             |
      | sessions.naming-strategy  | sequential       |
    And the google auth store has access "at-1" and refresh "rt-1"

  @wip
  Scenario: pull mode schedules an interval task instead of registering a watch at boot
    Given config:
      | comms.gmail.gmail/mode             | pull  |
      | comms.gmail.gmail/pull-interval-ms | 60000 |
    When the Google runtime component is started
    Then no outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/watch" was made
    And the gmail scheduled tasks include:
      | id          | interval-ms |
      | :gmail/pull | 60000       |

  @wip
  Scenario: a pull tick with two new INBOX messages gates and routes them like a push
    Given the gmail history cursor is "1000"
    And config:
      | comms.gmail.gmail/mode | pull |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
      | m-2 | t-2      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com     |
      | to      | yopp@tonotop.com    |
      | subject | Deploy window       |
      | body    | Can we ship Friday? |
    And the Gmail API returns message "m-2":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Lunch            |
      | body    | Tacos?           |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
      | model | type | content       |
      | echo  | text | Yes.          |
    When the Gmail pull timer ticks
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content                                    |
      | message | user         | #".*ada@tonotop.com.*Deploy window.*ship Friday.*" |
      | message | assistant    | Friday works.                                      |
    And session "gmail-t-2" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | #".*Lunch.*Tacos.*" |
      | message | assistant    | Yes.                |
    And the gmail history cursor is "1042"

  @wip
  Scenario: a pull tick that gets a server error from history.list retries on the next tick
    Given the gmail history cursor is "1000"
    And config:
      | comms.gmail.gmail/mode | pull |
    And the Gmail API history since "1000" fails with 500
    When the Gmail pull timer ticks
    Then the log has entries matching:
      | level | event               |
      | :warn | :gmail/pull-failed  |
    And the gmail history cursor is "1000"

    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | Retry works      |
      | body    | Second try       |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Got it. |
    When the Gmail pull timer ticks
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content    |
      | message | user         | #".*Retry works.*" |
      | message | assistant    | Got it.             |

  @wip
  Scenario: a first pull tick with no stored cursor seeds it from the newest history id without processing anything
    Given config:
      | comms.gmail.gmail/mode | pull |
    And the Gmail API inbox lists messages:
      | id  | threadId | historyId |
      | m-9 | t-9      | 3000      |
    When the Gmail pull timer ticks
    Then the gmail history cursor is "3000"
    And the session count is 0

  @wip
  Scenario: push mode (the default) schedules no pull task
    When the Google runtime component is started
    Then the gmail scheduled tasks are empty
