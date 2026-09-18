Feature: Gmail comm
  A Gmail push carries only a history id. Isaac walks history from the
  last id it processed, fetches the new INBOX messages, gates them, and
  routes each thread to one session. Replies go out on the thread with
  the headers mail clients need. The push step hands the watch payload to
  the handler this module contributed to :isaac.google/handler, the way
  the Chat steps do. Bean: isaac-cr0o.

  Background:
    Given default Grover setup in "/test/gmail"
    And config:
      | log.output                   | memory              |
      | comms.gmail.gmail/account    | yopp@tonotop.com    |
      | comms.gmail.gmail/allow-from | ["ada@tonotop.com"] |
      | comms.gmail.gmail/crew       | main                |
      | sessions.naming-strategy     | sequential          |
    And the google auth store has access "at-1" and refresh "rt-1"
    And the gmail history cursor is "1000"

  Scenario: a watch push after two new INBOX messages starts one turn per thread
    Given the Gmail API history since "1000" adds messages:
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
    When Gmail pushes a watch notification with history id "1042"
    Then session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content                                    |
      | message | user         | #".*ada@tonotop.com.*Deploy window.*ship Friday.*" |
      | message | assistant    | Friday works.                                      |
    And session "gmail-t-2" has transcript matching:
      | type    | message.role | message.content     |
      | message | user         | #".*Lunch.*Tacos.*" |
      | message | assistant    | Yes.                |
    And the gmail history cursor is "1042"

  Scenario: a reply goes out on the originating thread with the headers clients need
    Given the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from       | ada@tonotop.com     |
      | to         | yopp@tonotop.com    |
      | subject    | Deploy window       |
      | message-id | <abc@tonotop.com>   |
      | body       | Can we ship Friday? |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
    When Gmail pushes a watch notification with history id "1042"
    Then an outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/messages/send" matches:
      | method                | POST        |
      | headers.Authorization | Bearer at-1 |
      | body.threadId         | t-1         |
    And the sent mail decodes to:
      | To          | ada@tonotop.com   |
      | Subject     | Re: Deploy window |
      | In-Reply-To | <abc@tonotop.com> |
      | References  | <abc@tonotop.com> |
      | text        | Friday works.     |

  Scenario: an already-processed push starts nothing
    Given the gmail history cursor is "1042"
    When Gmail pushes a watch notification with history id "1042"
    Then the session count is 0
    And grover records zero provider requests
    And no outbound HTTP request to "https://gmail.googleapis.com/gmail/v1/users/me/history" was made

  Scenario: a stale cursor resyncs from the inbox and continues
    Given the Gmail API history since "1000" is gone
    And the Gmail API inbox lists messages:
      | id  | threadId | historyId |
      | m-7 | t-7      | 2001      |
    And the Gmail API returns message "m-7":
      | from    | ada@tonotop.com  |
      | to      | yopp@tonotop.com |
      | subject | After the gap    |
      | body    | Still there?     |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Yes.    |
    When Gmail pushes a watch notification with history id "2050"
    Then the log has entries matching:
      | level | event         | from | to   |
      | :warn | :gmail/resync | 1000 | 2001 |
    And session "gmail-t-7" has transcript matching:
      | type    | message.role | message.content       |
      | message | user         | #".*After the gap.*"  |
      | message | assistant    | Yes.                  |
    And the gmail history cursor is "2001"

  Scenario: sent mail, label-only changes, and unknown senders never start a turn
    Given the Gmail API history since "1000" contains:
      | kind         | id  | threadId | labelIds   |
      | labelAdded   | m-3 | t-3      | STARRED    |
      | messageAdded | m-4 | t-4      | SENT       |
      | messageAdded | m-5 | t-5      | INBOX      |
    And the Gmail API returns message "m-5":
      | from    | mallory@example.com |
      | to      | yopp@tonotop.com    |
      | subject | Prize               |
      | body    | You won             |
    When Gmail pushes a watch notification with history id "1099"
    Then the session count is 0
    And grover records zero provider requests
    And the log has entries matching:
      | level  | event                  | reason     |
      | :debug | :gmail/message-dropped | :not-inbox |
      | :debug | :gmail/message-dropped | :sender    |
    And the gmail history cursor is "1099"
