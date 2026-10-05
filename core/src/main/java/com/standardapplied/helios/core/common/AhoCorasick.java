/* Copyright (c) 2026 Standard Applied Intelligence Labs | SPDX-License-Identifier: MIT */

package com.standardapplied.helios.core.common;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Aho-Corasick automaton over 7-bit ASCII patterns, built in three steps: insert every pattern into
 * a trie, compute each state's failure link breadth-first, then chain each state to the nearest
 * shorter suffix state that ends a pattern. State {@code 0} is the root.
 */
final class AhoCorasick {

  static final int ALPHABET = 128;
  private static final int NONE = -1;

  private final int[] patternLengths;
  private final int[] gotoChildren;
  private final int[] failure;
  private final int[] outputPatternId;
  private final int[] outputNext;

  private AhoCorasick(
      int[] patternLengths,
      int[] gotoChildren,
      int[] failure,
      int[] outputPatternId,
      int[] outputNext) {
    this.patternLengths = patternLengths;
    this.gotoChildren = gotoChildren;
    this.failure = failure;
    this.outputPatternId = outputPatternId;
    this.outputNext = outputNext;
  }

  /**
   * Build the automaton matching {@code patterns}; a pattern's id is its index. Where two patterns
   * are equal, the lower id is the one reported.
   */
  static AhoCorasick build(List<byte[]> patterns) {
    var trie = new Trie();
    for (var pid = 0; pid < patterns.size(); pid++) {
      trie.insert(patterns.get(pid), pid);
    }
    var breadthFirst = new ArrayList<Integer>();
    var failure = trie.failureLinks(breadthFirst);
    var outputNext = trie.outputChains(failure, breadthFirst);
    var lengths = patterns.stream().mapToInt(p -> p.length).toArray();
    return new AhoCorasick(lengths, trie.flatGoto(), failure, trie.outputs(), outputNext);
  }

  /** The state reached from {@code state} on the ASCII byte {@code b}, following failure links. */
  int next(int state, int b) {
    var current = state;
    while (current != 0 && gotoChildren[current * ALPHABET + b] == NONE) {
      current = failure[current];
    }
    var child = gotoChildren[current * ALPHABET + b];
    return child != NONE ? child : current;
  }

  /** Report the id of every pattern that ends at {@code state}, longest first. */
  void forEachMatch(int state, IntConsumer patternId) {
    if (outputPatternId[state] != NONE) {
      patternId.accept(outputPatternId[state]);
    }
    for (var t = outputNext[state]; t != NONE; t = outputNext[t]) {
      patternId.accept(outputPatternId[t]);
    }
  }

  int patternLength(int patternId) {
    return patternLengths[patternId];
  }

  private static final class Trie {

    private final List<int[]> gotoTable = new ArrayList<>();
    private final List<Integer> output = new ArrayList<>();

    Trie() {
      addState();
    }

    private int addState() {
      var row = new int[ALPHABET];
      Arrays.fill(row, NONE);
      gotoTable.add(row);
      output.add(NONE);
      return gotoTable.size() - 1;
    }

    void insert(byte[] pattern, int pid) {
      var cur = 0;
      for (var b : pattern) {
        var row = gotoTable.get(cur);
        var idx = b & 0x7F;
        if (row[idx] == NONE) {
          row[idx] = addState();
        }
        cur = row[idx];
      }
      if (output.get(cur) == NONE) {
        output.set(cur, pid);
      }
    }

    /** Failure link of every state; fills {@code order} with the non-root states breadth-first. */
    int[] failureLinks(List<Integer> order) {
      var fail = new int[gotoTable.size()];
      for (var c = 0; c < ALPHABET; c++) {
        if (gotoTable.get(0)[c] != NONE) {
          order.add(gotoTable.get(0)[c]);
        }
      }
      for (var i = 0; i < order.size(); i++) {
        var u = order.get(i);
        var row = gotoTable.get(u);
        for (var c = 0; c < ALPHABET; c++) {
          if (row[c] != NONE) {
            fail[row[c]] = longestSuffixChild(fail, fail[u], c);
            order.add(row[c]);
          }
        }
      }
      return fail;
    }

    private int longestSuffixChild(int[] fail, int start, int c) {
      var f = start;
      while (f != 0 && gotoTable.get(f)[c] == NONE) {
        f = fail[f];
      }
      var g = gotoTable.get(f)[c];
      return g != NONE ? g : 0;
    }

    /**
     * Each state's nearest proper suffix state that ends a pattern, or none. The root's children
     * are left with none, so an empty pattern is not reported right after their byte.
     */
    int[] outputChains(int[] fail, List<Integer> order) {
      var next = new int[gotoTable.size()];
      Arrays.fill(next, NONE);
      for (var u : order) {
        for (var v : gotoTable.get(u)) {
          if (v != NONE) {
            next[v] = output.get(fail[v]) != NONE ? fail[v] : next[fail[v]];
          }
        }
      }
      return next;
    }

    int[] flatGoto() {
      var flat = new int[gotoTable.size() * ALPHABET];
      for (var i = 0; i < gotoTable.size(); i++) {
        System.arraycopy(gotoTable.get(i), 0, flat, i * ALPHABET, ALPHABET);
      }
      return flat;
    }

    int[] outputs() {
      return output.stream().mapToInt(Integer::intValue).toArray();
    }
  }
}
