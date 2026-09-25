Feature: Gmail comm
  A Gmail push carries only a history id. Isaac walks history from the
  last id it processed, fetches the new INBOX messages, gates them, and
  routes each thread to one session. Replies go out on the thread with
  the headers mail clients need. The push step hands the watch payload to
  the handler this module contributed to :isaac.google/handler, the way
  the Chat steps do. Bean: isaac-cr0o. Senders are admitted by gmail-routes
  (isaac-sb6d), not by an allow-list.

  Background:
    Given default Grover setup in "/test/gmail"
    And config:
      | log.output                   | memory              |
      | google.tonotop.project       | marigold            |
      | comms.gmail.gmail/account    | yopp@tonotop.com    |
      | comms.gmail.gmail/crew       | main                |
      | gmail-routes.team.order      | 90                  |
      | gmail-routes.team.match.from | ada@tonotop.com     |
      | gmail-routes.team.action     | converse            |
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

  Scenario: sent mail, label-only changes, and senders no route names never start a turn
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
    And the log has entries matching:
      | level | event           | from                |
      | :info | :gmail/unrouted | mallory@example.com |
    And message "m-5" carries label "isaac/unrouted"
    And the gmail history cursor is "1099"

  Scenario: a *@domain route admits the domain only when Gmail authenticates it (isaac-dymn, isaac-sb6d)
    Given config:
      | gmail-routes.domain.order      | 10            |
      | gmail-routes.domain.match.from | *@tonotop.com |
      | gmail-routes.domain.action     | converse      |
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-6 | t-6      |
      | m-7 | t-7      |
    And the Gmail API returns message "m-6":
      | from         | Grace Hopper <grace@tonotop.com>                                                                          |
      | to           | yopp@tonotop.com                                                                                          |
      | subject      | Ship it                                                                                                   |
      | body         | Friday?                                                                                                   |
      | auth-results | mx.google.com; dkim=pass header.d=tonotop.com; spf=pass smtp.mailfrom=tonotop.com; dmarc=pass header.from=tonotop.com |
    And the Gmail API returns message "m-7":
      | from         | Grace Hopper <grace@tonotop.com>                                     |
      | to           | yopp@tonotop.com                                                     |
      | subject      | Wire me money                                                        |
      | body         | Urgently                                                             |
      | auth-results | mx.google.com; dkim=none; spf=softfail; dmarc=fail header.from=tonotop.com |
    And the following model responses are queued:
      | model | type | content |
      | echo  | text | Friday. |
    When Gmail pushes a watch notification with history id "1102"
    Then session "gmail-t-6" has transcript matching:
      | type    | message.role | message.content          |
      | message | user         | #".*Ship it.*Friday\?.*" |
      | message | assistant    | Friday.                  |
    And the session count is 1
    And message "m-6" carries label "isaac/domain"
    And the log has entries matching:
      | level | event                  | reason           |
      | :warn | :gmail/message-dropped | :unauthenticated |


  Scenario: a turn that only answers in text sends one reply (isaac-3t0z, kept by isaac-iwio)
    Given the crew "main" allows tools: "gmail__send"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com     |
      | to      | yopp@tonotop.com    |
      | subject | Deploy window       |
      | body    | Can we ship Friday? |
    And the following model responses are queued:
      | model | type | content       |
      | echo  | text | Friday works. |
    When Gmail pushes a watch notification with history id "1042"
    Then the Gmail API sent 1 message

  # One send tool (isaac-iwio): gmail declares a send-schema so comm__send
  # can compose mail; the response is the text the turn ends with and the
  # comm mails it; a comm__send into the origin thread is an additional
  # message, so both go out.

  Scenario: comm__send with gmail.to and gmail.subject sends a new email (isaac-iwio)
    Given the crew "main" allows tools: "comm/send"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com          |
      | to      | yopp@tonotop.com         |
      | subject | Deploy window            |
      | body    | Can you ask Grace today? |
    And the following model responses are queued:
      | model | type | content     | tool_call  | arguments                                                                                                                 |
      | echo  |      |             | comm__send | {"comm":"gmail","gmail.to":"grace@tonotop.com","gmail.subject":"Deploy window","content":"Ada asks: can we ship Friday?"} |
      | echo  | text | Asked Grace. |            |                                                                                                                           |
    When Gmail pushes a watch notification with history id "1042"
    And the delivery worker ticks
    Then the Gmail API sent 2 messages
    And the sent mail to "grace@tonotop.com" decodes to:
      | To      | grace@tonotop.com             |
      | Subject | Deploy window                 |
      | text    | Ada asks: can we ship Friday? |

  Scenario: comm__send replying into the origin thread, then the answer — both go out (isaac-iwio)
    Given the crew "main" allows tools: "comm/send"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from       | ada@tonotop.com     |
      | to         | yopp@tonotop.com    |
      | subject    | Deploy window       |
      | message-id | <abc@tonotop.com>   |
      | body       | Can we ship Friday? |
    And the following model responses are queued:
      | model | type | content       | tool_call  | arguments                                                     |
      | echo  |      |               | comm__send | {"comm":"gmail","gmail.thread":"t-1","content":"Looking now."} |
      | echo  | text | Friday works. |            |                                                               |
    When Gmail pushes a watch notification with history id "1042"
    And the delivery worker ticks
    Then the Gmail API sent 2 messages

  # Attachments (isaac-8hi7): a multipart/mixed raw message carries the files.

  Scenario: comm__send with gmail.to, gmail.subject and an attachment sends one multipart email carrying the file (isaac-8hi7)
    Given the crew "main" allows tools: "comm/send"
    And a file "report.pdf" exists in the session working directory with content "%PDF-1.4 stub"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com            |
      | to      | yopp@tonotop.com           |
      | subject | Report                     |
      | body    | Send Grace the report.     |
    And the following model responses are queued:
      | model | type | content | tool_call  | arguments                                                                                                              |
      | echo  |      |         | comm__send | {"comm":"gmail","gmail.to":"grace@tonotop.com","gmail.subject":"Report","content":"Attached.","attachments":["report.pdf"]} |
      | echo  | text | Sent.   |            |                                                                                                                        |
    When Gmail pushes a watch notification with history id "1042"
    And the delivery worker ticks
    Then the Gmail API sent 2 messages
    And the sent mail to "grace@tonotop.com" decodes to:
      | To          | grace@tonotop.com |
      | Subject     | Report            |
      | text        | Attached.         |
      | attachments | report.pdf        |

  # Inbound attachments (isaac-e2zb): an email's attachment parts are downloaded
  # under the session's working directory before the turn, and the framed
  # input names them.

  @wip
  Scenario: an email attachment is saved under the session working directory and the turn is told (isaac-e2zb)
    Given the crew "main" allows tools: "fs/*"
    And the Gmail API history since "1000" adds messages:
      | id  | threadId |
      | m-1 | t-1      |
    And the Gmail API returns message "m-1":
      | from    | ada@tonotop.com    |
      | to      | yopp@tonotop.com   |
      | subject | The report         |
      | body    | Attached, as asked |
    And the Gmail API returns attachment "att-1" of message "m-1" named "report.pdf" with content "%PDF-1.4 stub"
    And the following model responses are queued:
      | model | type | content        |
      | echo  | text | Got the report. |
    When Gmail pushes a watch notification with history id "1042"
    Then the file "attachments/m-1/report.pdf" under the session working directory contains "%PDF-1.4 stub"
    And session "gmail-t-1" has transcript matching:
      | type    | message.role | message.content                                                     |
      | message | user         | #"(?s).*\[attachment: report\.pdf \(application/pdf, .*\) at attachments/m-1/report\.pdf\].*" |
      | message | assistant    | Got the report.                                                     |
