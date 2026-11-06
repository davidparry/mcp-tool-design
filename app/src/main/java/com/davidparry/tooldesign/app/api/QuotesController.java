package com.davidparry.tooldesign.app.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.davidparry.tooldesign.app.api.ApiModels.ExchangeQuote;
import com.davidparry.tooldesign.app.api.ApiModels.ExchangeQuoteRequest;
import com.davidparry.tooldesign.app.api.ApiModels.ExchangeView;
import com.davidparry.tooldesign.app.api.ApiModels.ReturnQuote;
import com.davidparry.tooldesign.app.api.ApiModels.ReturnQuoteRequest;
import com.davidparry.tooldesign.app.api.ApiModels.ReturnView;
import com.davidparry.tooldesign.app.api.ApiModels.SubmitQuoteRequest;
import com.davidparry.tooldesign.app.domain.Quotes.CommittedAction;
import com.davidparry.tooldesign.app.domain.Quotes.Quote;
import com.davidparry.tooldesign.app.service.QuoteService;
import com.davidparry.tooldesign.app.service.QuoteService.ExchangeRequest;
import com.davidparry.tooldesign.app.service.QuoteService.ReturnRequest;
import com.davidparry.tooldesign.app.service.SubmissionService;
import com.davidparry.tooldesign.app.service.SubmissionService.Submission;

@RestController
@RequestMapping("/api")
@Tag(name = "Quotes and submissions", description = "Draft quotes for exchanges and returns, and committing them")
public class QuotesController {

    private final QuoteService quotes;

    private final SubmissionService submissions;

    public QuotesController(QuoteService quotes, SubmissionService submissions) {
        this.quotes = quotes;
        this.submissions = submissions;
    }

    @PostMapping("/exchange-quotes")
    @Operation(operationId = "create_exchange_quote", summary = "Create an exchange quote",
            description = "Validates and prices exchanging an order item for another variant of the same product, shipped from one warehouse by one delivery method. Returns the price difference, the shipping charge, the additional cost to the customer, the estimated arrival, and when the quote expires (30 minutes after it's created). Creates a draft quote only. It doesn't commit an exchange, reserve stock, or charge the customer.")
    public ResponseEntity<ExchangeQuote> exchangeQuote(@Valid @RequestBody ExchangeQuoteRequest body) {
        Quote q = this.quotes.exchange(new ExchangeRequest(body.orderReference(), body.itemId(), body.replacementSku(),
                body.quantity(), body.warehouseId(), body.deliveryMethod()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ExchangeQuote(q.quoteId(), q.status().name(),
                q.orderReference(), q.itemId(), q.sku(), q.replacementSku(), q.quantity(), q.warehouseId(),
                q.deliveryMethod(), q.estimatedArrival(), q.priceDifference(), q.shippingCharge(), q.total(),
                q.createdAt(), q.expiresAt()));
    }

    @PostMapping("/return-quotes")
    @Operation(operationId = "create_return_quote", summary = "Create a return quote",
            description = "Validates and prices returning some or all units of an order item. Returns the item refund, any return shipping or restocking fee, the refund amount, and when the quote expires (30 minutes after it's created). Creates a draft quote only. It doesn't commit a return or issue a refund.")
    public ResponseEntity<ReturnQuote> returnQuote(@Valid @RequestBody ReturnQuoteRequest body) {
        Quote q = this.quotes.returnQuote(new ReturnRequest(body.orderReference(), body.itemId(), body.quantity()));
        return ResponseEntity.status(HttpStatus.CREATED).body(new ReturnQuote(q.quoteId(), q.status().name(),
                q.orderReference(), q.itemId(), q.sku(), q.quantity(), q.itemRefund(), q.returnShippingFee(),
                q.restockingFee(), q.total(), q.createdAt(), q.expiresAt()));
    }

    @PostMapping("/exchanges")
    @Operation(operationId = "submit_exchange_quote", summary = "Submit an exchange quote",
            description = "Commits the exchange an open exchange quote describes: takes the replacement from the quote's warehouse stock, ships it by the quote's method, and charges the additional cost. Checks again that the quote hasn't expired, that the item is still eligible, and that the stock is still there, and changes nothing if any check fails. Submitting a quote that was already submitted returns the original exchange and commits nothing new.")
    public ResponseEntity<ExchangeView> submitExchange(@Valid @RequestBody SubmitQuoteRequest body) {
        Submission submission = this.submissions.submitExchange(body.quoteId());
        CommittedAction a = submission.action();
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new ExchangeView(a.actionId(), a.quoteId(), a.status().name(), a.orderReference(), a.itemId(),
                        a.sku(), a.replacementSku(), a.quantity(), a.warehouseId(), a.deliveryMethod(),
                        a.estimatedArrival(), a.amount(), a.submittedAt()));
    }

    @PostMapping("/returns")
    @Operation(operationId = "submit_return_quote", summary = "Submit a return quote",
            description = "Commits the return an open return quote describes and schedules the refund for when the item arrives. Checks again that the quote hasn't expired and that the item is still eligible, and changes nothing if a check fails. Submitting a quote that was already submitted returns the original return and commits nothing new.")
    public ResponseEntity<ReturnView> submitReturn(@Valid @RequestBody SubmitQuoteRequest body) {
        Submission submission = this.submissions.submitReturn(body.quoteId());
        CommittedAction a = submission.action();
        return ResponseEntity.status(submission.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new ReturnView(a.actionId(), a.quoteId(), a.status().name(), a.orderReference(), a.itemId(),
                        a.sku(), a.quantity(), a.amount(), a.submittedAt()));
    }
}
