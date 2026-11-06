package com.davidparry.tooldesign.benchmark.run;

import com.davidparry.tooldesign.benchmark.model.DefinitionOnlyTool;

/**
 * What both arms share word for word: the system prompt and the {@code final_answer} card. Only the MCP tools differ
 * between arms.
 */
public final class Conversation {

    public static final String SYSTEM_PROMPT = """
            You are a customer support agent for Trailhead Pet Supply, an online pet supply store.

            Today is Friday, November 6, 2026. The store's time zone is America/Chicago (UTC-06:00). The customer \
            has already been verified, so you can look up and act on any order they mention.

            Use the tools for every fact you give the customer: order status and dates, items, eligibility, prices, \
            delivery dates, fees, and refunds. Don't guess, and don't work out an amount yourself when a tool can \
            give it to you.

            Never submit an exchange or a return unless the customer has explicitly approved one specific option \
            you showed them. When the customer only asks what's possible, show the options and wait for their \
            answer.

            If the customer's description could mean more than one item in the order, ask which one they mean \
            instead of picking one.

            The customer never sees text you write outside a tool call. Your reply reaches them only through \
            final_answer, so when you're ready to answer, call final_answer once with your message and the facts \
            it states instead of writing the reply as text.""";

    public static final String FINAL_ANSWER = "final_answer";

    /**
     * Sent once per customer message when the model replies in plain text. A second plain reply ends that message
     * without a card.
     */
    public static final String REMINDER = "[Support system] Your reply above wasn't delivered: the customer only "
            + "sees replies sent with the final_answer tool. Call final_answer now, with that reply in the message "
            + "field and the facts it states in the other fields.";

    public static final String FINAL_ANSWER_DESCRIPTION = "Send your reply to the customer. Call it once per "
            + "customer message, after you have every fact you need. Put the facts your message states in the "
            + "matching fields and leave out fields that don't apply.";

    public static final String FINAL_ANSWER_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "Your reply to the customer, exactly as they'll read it"
                },
                "outcome": {
                  "type": "string",
                  "description": "What the reply does",
                  "enum": ["order_status", "refund_preview", "options_available", "no_matching_option", "ineligible",
                           "needs_clarification", "submitted", "submission_failed", "awaiting_approval", "other"]
                },
                "order_reference": {
                  "type": "string",
                  "description": "The order the reply is about, such as 1042"
                },
                "order_status": {
                  "type": "string",
                  "description": "The order's status, when the reply states it",
                  "enum": ["PLACED", "SHIPPED", "DELIVERED", "CANCELLED"]
                },
                "status_date": {
                  "type": "string",
                  "description": "The delivery date, or the expected delivery date when it hasn't arrived, as YYYY-MM-DD"
                },
                "refund_amount": {
                  "type": "string",
                  "description": "The refund the customer would get, in USD, such as 60.00"
                },
                "options": {
                  "type": "array",
                  "description": "The exchange options the reply presents",
                  "items": {
                    "type": "object",
                    "properties": {
                      "ships_from": { "type": "string", "description": "Where the replacement ships from" },
                      "method": { "type": "string", "enum": ["GROUND", "EXPRESS"] },
                      "arrives_by": { "type": "string", "description": "YYYY-MM-DD" },
                      "additional_cost": { "type": "string", "description": "What the customer pays, in USD, such as 11.99" },
                      "meets_all_constraints": {
                        "type": "boolean",
                        "description": "Whether it meets every limit the customer gave"
                      },
                      "quote_reference": { "type": "string", "description": "The quote ID, when there is one" }
                    },
                    "required": ["ships_from", "method", "arrives_by", "additional_cost", "meets_all_constraints"]
                  }
                },
                "blocking_reasons": {
                  "type": "array",
                  "description": "Why the customer can't have what they asked for, when they can't",
                  "items": {
                    "type": "string",
                    "enum": ["deadline", "cost_limit", "out_of_stock", "policy_window", "policy_category",
                             "not_delivered", "ambiguous_item", "other"]
                  }
                },
                "clarification_question": {
                  "type": "string",
                  "description": "The question you're asking the customer, when you need an answer to go on"
                },
                "submitted_reference": {
                  "type": "string",
                  "description": "The exchange or return ID, when one was submitted"
                }
              },
              "required": ["message", "outcome"]
            }""";

    private Conversation() {
    }

    public static DefinitionOnlyTool finalAnswerTool() {
        return DefinitionOnlyTool.of(FINAL_ANSWER, FINAL_ANSWER_DESCRIPTION, FINAL_ANSWER_SCHEMA);
    }
}
