package com.davidparry.tooldesign.workflow.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.davidparry.tooldesign.workflow.api.ApiTypes.Money;
import com.davidparry.tooldesign.workflow.api.ApiTypes.OrderItem;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher.Ambiguous;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher.Line;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher.Matched;
import com.davidparry.tooldesign.workflow.matching.ItemMatcher.NotFound;

class ItemMatcherTest {

    static Line line(String itemId, String product, String category, String sku, String size, String color, int qty) {
        Money price = new Money("10.00", "USD");
        return new Line(new OrderItem(itemId, sku.split("-")[0], product, sku, size, color, qty, price, price, 0, 0),
                category);
    }

    static final Line TRAILBLAZER_M_BLUE = line("1042-1", "Trailblazer No-Pull Dog Harness", "HARNESS", "TRB-M-BLU",
            "M", "Blue", 1);

    static final Line ROPE_TOY = line("1042-2", "Squeaky Rope Toy", "TOY", "RPE-STD", null, null, 1);

    static final Line CITYSTROLL_M = line("1037-2", "CityStroll Step-In Harness", "HARNESS", "CTY-M-BLK", "M",
            "Black", 1);

    static final Line TRAILBLAZER_M_RED = line("1045-2", "Trailblazer No-Pull Dog Harness", "HARNESS", "TRB-M-RED",
            "M", "Red", 1);

    static final Line FETCH_BALLS = line("1022-2", "Fetch Ball 3-Pack", "TOY", "BAL-3PK", null, null, 1);

    @Test
    void theMediumHarnessIsTheHarnessNotTheToy() {
        assertThat(ItemMatcher.match("medium dog harness", List.of(TRAILBLAZER_M_BLUE, ROPE_TOY)))
                .isEqualTo(new Matched(TRAILBLAZER_M_BLUE));
        assertThat(ItemMatcher.match("the rope toy", List.of(TRAILBLAZER_M_BLUE, ROPE_TOY)))
                .isEqualTo(new Matched(ROPE_TOY));
    }

    @Test
    void twoMediumHarnessesAreAmbiguous() {
        assertThat(ItemMatcher.match("the medium harness", List.of(TRAILBLAZER_M_BLUE, CITYSTROLL_M)))
                .isEqualTo(new Ambiguous(List.of(TRAILBLAZER_M_BLUE, CITYSTROLL_M)));
        assertThat(ItemMatcher.match("medium Trailblazer", List.of(TRAILBLAZER_M_BLUE, TRAILBLAZER_M_RED)))
                .isInstanceOf(Ambiguous.class);
    }

    @Test
    void aNameOrColorSettlesWhatTheCategoryCant() {
        assertThat(ItemMatcher.match("the medium CityStroll harness", List.of(TRAILBLAZER_M_BLUE, CITYSTROLL_M)))
                .isEqualTo(new Matched(CITYSTROLL_M));
        assertThat(ItemMatcher.match("red medium Trailblazer", List.of(TRAILBLAZER_M_BLUE, TRAILBLAZER_M_RED)))
                .isEqualTo(new Matched(TRAILBLAZER_M_RED));
    }

    @Test
    void toyIsOnlyACategoryWordSoTwoToysAreAmbiguous() {
        assertThat(ItemMatcher.match("the toy", List.of(ROPE_TOY, FETCH_BALLS)))
                .isEqualTo(new Ambiguous(List.of(ROPE_TOY, FETCH_BALLS)));
        assertThat(ItemMatcher.match("the balls", List.of(ROPE_TOY, FETCH_BALLS))).isEqualTo(new Matched(FETCH_BALLS));
    }

    @Test
    void plurals() {
        Line chewBones = line("1023-1", "Durable Chew Bone", "TOY", "CHW-M", "M", null, 3);
        assertThat(ItemMatcher.match("three chew bones", List.of(chewBones))).isEqualTo(new Matched(chewBones));
        Line treats = line("1026-1", "Salmon Training Treats, 12 oz", "TREATS", "TRT-12OZ", null, null, 2);
        assertThat(ItemMatcher.match("the training treats", List.of(treats, ROPE_TOY)))
                .isEqualTo(new Matched(treats));
    }

    @Test
    void inchSizesMatchTheCratesSize() {
        Line crate = line("1044-1", "Folding Wire Crate", "CRATE", "CRT-36", "36 in", null, 1);
        assertThat(ItemMatcher.match("the 36-inch crate", List.of(crate, ROPE_TOY))).isEqualTo(new Matched(crate));
        assertThat(ItemMatcher.match("the 42-inch crate", List.of(crate))).isInstanceOf(NotFound.class);
    }

    @Test
    void aDescriptionThatFitsNothingMatchesNothing() {
        assertThat(ItemMatcher.match("the leash", List.of(TRAILBLAZER_M_BLUE, ROPE_TOY))).isInstanceOf(NotFound.class);
    }

    @Test
    void aSingleLineMatchesUnlessTheDescriptionContradictsIt() {
        assertThat(ItemMatcher.match("it", List.of(TRAILBLAZER_M_BLUE))).isEqualTo(new Matched(TRAILBLAZER_M_BLUE));
        assertThat(ItemMatcher.match("the large harness", List.of(TRAILBLAZER_M_BLUE))).isInstanceOf(NotFound.class);
    }

    @Test
    void aPossessiveIsntASize() {
        assertThat(ItemMatcher.match("my dog's harness", List.of(TRAILBLAZER_M_BLUE, ROPE_TOY)))
                .isEqualTo(new Matched(TRAILBLAZER_M_BLUE));
    }

    @Test
    void sizesHaveOneSpelling() {
        assertThat(Sizes.canonical("large")).isEqualTo("L");
        assertThat(Sizes.canonical("Extra Large")).isEqualTo("XL");
        assertThat(Sizes.canonical("x-large")).isEqualTo("XL");
        assertThat(Sizes.canonical("42-inch")).isEqualTo("42 IN");
        assertThat(Sizes.canonical("42 in")).isEqualTo("42 IN");
        assertThat(Sizes.canonical("42\"")).isEqualTo("42 IN");
        assertThat(Sizes.canonical("small/medium")).isEqualTo("S/M");
        assertThat(Sizes.canonical("S/M")).isEqualTo("S/M");
        assertThat(Sizes.canonical("medium size")).isEqualTo("M");
        assertThat(Sizes.canonical("huge")).isNull();
    }
}
