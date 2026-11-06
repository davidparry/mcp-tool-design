package com.davidparry.tooldesign.workflow.tools;

import java.util.List;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import com.davidparry.tooldesign.workflow.api.ApiProblem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.Eligibility;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderDetail;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnQuote;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnQuoteRequest;
import com.davidparry.tooldesign.workflow.api.ApiTypes.ReturnView;
import com.davidparry.tooldesign.workflow.api.OrderApi;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher;
import com.davidparry.tooldesign.workflow.tools.Inputs.InvalidInput;
import com.davidparry.tooldesign.workflow.tools.Results.Item;
import com.davidparry.tooldesign.workflow.tools.Results.ReturnPreview;
import com.davidparry.tooldesign.workflow.tools.Results.ReturnSubmission;

@Component
public class ReturnTools {

    private final OrderApi api;

    public ReturnTools(OrderApi api) {
        this.api = api;
    }

    @McpTool(name = "preview_return", title = "Preview a return", generateOutputSchema = true,
            description = "Check whether an item in an order can be returned and preview the refund. Matches the "
                    + "item from the customer's description, checks the return policy, and quotes the refund after "
                    + "any return shipping or restocking fee. Creates a draft return quote that expires in 30 "
                    + "minutes. It never submits the return or issues a refund.",
            annotations = @McpTool.McpAnnotations(title = "Preview a return", readOnlyHint = false,
                    destructiveHint = false, idempotentHint = false, openWorldHint = false))
    public ReturnPreview previewReturn(
            @McpToolParam(description = "The order reference, such as 1019") String orderReference,
            @McpToolParam(description = "The item to return, in the customer's words, such as \"dog bed\"") String itemDescription,
            @McpToolParam(description = "How many units to return. Defaults to 1.", required = false) Integer quantity) {
        int units;
        try {
            units = Inputs.quantity(quantity);
        }
        catch (InvalidInput e) {
            return refusal("invalid_request", e.getMessage(), orderReference, null, null);
        }
        OrderDetail order;
        try {
            order = this.api.order(orderReference);
        }
        catch (ApiProblem problem) {
            if (problem.status() == 404) {
                return refusal("order_not_found", "There's no order " + orderReference + ".", orderReference, null,
                        null);
            }
            throw problem;
        }
        List<ItemMatcher.Line> lines = new OrderLines(this.api).lines(this.api.items(orderReference).items());
        ItemMatcher.Result match = ItemMatcher.match(itemDescription, lines);
        if (match instanceof ItemMatcher.Ambiguous(List<ItemMatcher.Line> candidates)) {
            return refusal("needs_clarification", "\"" + itemDescription + "\" fits " + candidates.size()
                    + " items in order " + orderReference + ". Ask the customer which one they mean.",
                    orderReference, null, OrderLines.items(candidates));
        }
        if (match instanceof ItemMatcher.NotFound(List<ItemMatcher.Line> all)) {
            return refusal("item_not_found", "Nothing in order " + orderReference + " fits \"" + itemDescription
                    + "\". These are the order's items.", orderReference, null, OrderLines.items(all));
        }
        OrderItem item = ((ItemMatcher.Matched) match).line().item();
        Item matched = OrderLines.item(item);

        Eligibility eligibility = this.api.returnEligibility(orderReference, item.itemId());
        if (!eligibility.eligible()) {
            return new ReturnPreview("ineligible", eligibility.reason(), orderReference, matched, null,
                    ExchangeTools.ineligible(eligibility, order), null, null, null, null, null, null, null, null,
                    eligibility.policy().description(), "Tell the customer why this item can't be returned. "
                            + "Nothing was quoted.");
        }
        if (units > eligibility.maxQuantity()) {
            return refusal("invalid_request", "Only " + eligibility.maxQuantity() + " of this item can be returned.",
                    orderReference, matched, null);
        }
        ReturnQuote quote = this.api.createReturnQuote(new ReturnQuoteRequest(orderReference, item.itemId(), units));
        String fees = quote.itemRefund().value().compareTo(quote.refundAmount().value()) == 0 ? " with no fees"
                : " ($" + quote.itemRefund().amount() + " less $" + quote.returnShippingFee().amount()
                        + " return shipping and $" + quote.restockingFee().amount() + " restocking)";
        return new ReturnPreview("refund_preview", "Returning " + units + " × " + item.productName() + " refunds $"
                + quote.refundAmount().amount() + fees + ".", orderReference, matched, null, null, units,
                quote.itemRefund(), quote.returnShippingFee(), quote.restockingFee(), quote.refundAmount(),
                quote.quoteId(), quote.expiresAt(), eligibility.windowEndsOn(), eligibility.policy().description(),
                "Tell the customer the refund. Call submit_return with this quoteId only after the customer "
                        + "approves the return.");
    }

    @McpTool(name = "submit_return", title = "Submit a return", generateOutputSchema = true,
            description = "Submit one return quote from preview_return after the customer explicitly approves it. "
                    + "Commits the return; the refund is issued when the item arrives back. The quote is checked "
                    + "again first: an expired quote or an item that can no longer be returned is refused with the "
                    + "reason, and nothing is committed. Submitting the same quote again returns the original "
                    + "return and changes nothing.",
            annotations = @McpTool.McpAnnotations(title = "Submit a return", readOnlyHint = false,
                    destructiveHint = true, idempotentHint = true, openWorldHint = false))
    public ReturnSubmission submitReturn(
            @McpToolParam(description = "The quoteId of the return the customer approved, such as RQ-6001") String quoteId) {
        try {
            OrderApi.Submitted<ReturnView> submitted = this.api.submitReturn(quoteId);
            ReturnView view = submitted.body();
            String message = submitted.created()
                    ? "Return " + view.returnId() + " is confirmed. The customer gets $" + view.refundAmount().amount()
                            + " back once the item arrives."
                    : "Quote " + quoteId + " was already submitted as return " + view.returnId()
                            + ". Nothing new was committed.";
            return new ReturnSubmission(submitted.created() ? "submitted" : "already_submitted", message,
                    view.returnId(), view.quoteId(), view.status(), view.orderReference(), view.itemId(),
                    view.quantity(), view.refundAmount(), view.submittedAt(),
                    submitted.created() ? "Tell the customer the return is confirmed."
                            : "Tell the customer the return was already in place. Don't submit again.");
        }
        catch (ApiProblem problem) {
            String outcome = switch (problem.code()) {
                case "quote_expired", "quote_not_found" -> problem.code();
                case "not_eligible" -> "ineligible";
                default -> "invalid_request";
            };
            String nextStep = "quote_expired".equals(outcome)
                    ? "Nothing was committed. Run preview_return again for a current quote and get the customer's "
                            + "approval again before submitting."
                    : "Nothing was committed. Tell the customer why.";
            return new ReturnSubmission(outcome, problem.detail(), null, quoteId, null, null, null, null, null, null,
                    nextStep);
        }
    }

    private static ReturnPreview refusal(String outcome, String message, String reference, Item matched,
            List<Item> candidates) {
        String nextStep = switch (outcome) {
            case "needs_clarification", "item_not_found" -> "Ask the customer which item they mean, then call "
                    + "preview_return again with that item's description.";
            default -> "Fix the request and call preview_return again.";
        };
        return new ReturnPreview(outcome, message, reference, matched, candidates, null, null, null, null, null, null,
                null, null, null, null, nextStep);
    }
}
