```
static std::vector<llama_vocab::id> llama_tokenize_internal(const llama_vocab & vocab, std::string raw_text, bool add_special, bool parse_special) {
    std::vector<llama_vocab::id> output;
    std::forward_list<fragment_buffer_variant> fragment_buffer;

    if (!raw_text.empty()) {
        fragment_buffer.emplace_front(raw_text, 0, raw_text.length());
        if (parse_special) tokenizer_st_partition(vocab, fragment_buffer);
    }

    switch (vocab.type) {
        case LLAMA_VOCAB_TYPE_SPM:
            {
                // OG tokenizer behavior:
                //
                // tokenizer.encode('', add_special_tokens=True)  returns [1]
                // tokenizer.encode('', add_special_tokens=False) returns []

                if (add_special && vocab.special_add_bos != 0) {
                    GGML_ASSERT(vocab.special_bos_id != -1);
                    output.push_back(vocab.special_bos_id);
                }

                for (const auto & fragment : fragment_buffer) {
                    if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_RAW_TEXT) {
                        // without adding this leading whitespace, we do not get the same results as the original tokenizer

                        // TODO: It's likely possible to get rid of this string copy entirely
                        //  by modifying llm_tokenizer_x to operate with string offsets like pre-tokenizer
                        //  and passing 'add space prefix' as bool argument
                        //
                        auto raw_text = fragment.raw_text.substr(fragment.offset, fragment.length);
                        if (&fragment == &fragment_buffer.front()) {
                            if (vocab.add_space_prefix) {
                                raw_text = " " + raw_text; // prefix with space if the first token is not special
                            }
                        }

#ifdef PRETOKENIZERDEBUG
                        LLAMA_LOG_WARN("TT: (%ld %ld %ld) '%s'\n", raw_text.length(), fragment.offset, fragment.length, raw_text.c_str());
#endif
                        llm_tokenizer_spm tokenizer(vocab);
                        llama_escape_whitespace(raw_text);
                        tokenizer.tokenize(raw_text, output);
                    } else { // if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_TOKEN)
                        output.push_back(fragment.token);
                    }
                }

                if (add_special && vocab.special_add_eos == 1) {
                    GGML_ASSERT(vocab.special_eos_id != -1);
                    output.push_back(vocab.special_eos_id);
                }
            } break;
        case LLAMA_VOCAB_TYPE_BPE:
            {
                if (add_special && vocab.special_add_bos == 1) {
                    GGML_ASSERT(vocab.special_bos_id != -1);
                    output.push_back(vocab.special_bos_id);
                }

                for (const auto & fragment : fragment_buffer) {
                    if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_RAW_TEXT) {
                        auto raw_text = fragment.raw_text.substr(fragment.offset, fragment.length);

#ifdef PRETOKENIZERDEBUG
                        LLAMA_LOG_WARN("TT: (%ld %ld %ld) '%s'\n", raw_text.length(), fragment.offset, fragment.length, raw_text.c_str());
#endif
                        llm_tokenizer_bpe tokenizer(vocab);
                        tokenizer.tokenize(raw_text, output);
                    } else { // if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_TOKEN)
                        output.push_back(fragment.token);
                    }
                }

                GGML_ASSERT(vocab.special_add_eos != 1);
            } break;
        case LLAMA_VOCAB_TYPE_WPM:
            {
                if (add_special) {
                    GGML_ASSERT(vocab.special_cls_id != -1);
                    output.push_back(vocab.special_cls_id);
                }

                for (const auto & fragment : fragment_buffer) {
                    if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_RAW_TEXT) {
                        auto raw_text = fragment.raw_text.substr(fragment.offset, fragment.length);

#ifdef PRETOKENIZERDEBUG
                        LLAMA_LOG_WARN("TT: (%ld %ld %ld) '%s'\n", raw_text.length(), fragment.offset, fragment.length, raw_text.c_str());
#endif
                        llm_tokenizer_wpm tokenizer(vocab);
                        tokenizer.tokenize(raw_text, output);
                    } else { // if (fragment.type == FRAGMENT_BUFFER_VARIANT_TYPE_TOKEN)
                        output.push_back(fragment.token);
                    }
                }

                if (add_special) {
                    GGML_ASSERT(vocab.special_sep_id != -1);
                    output.push_back(vocab.special_sep_id);
                }
            } break;
        case LLAMA_VOCAB_TYPE_NONE:
            GGML_ASSERT(false);
    }

    return output;
}

// ...

int32_t llama_tokenize(
    const struct llama_model * model,
                  const char * text,
                     int32_t   text_len,
                 llama_token * tokens,
                     int32_t   n_tokens_max,
                        bool   add_special,
                        bool   parse_special) {
    auto res = llama_tokenize_internal(model->vocab, std::string(text, text_len), add_special, parse_special);

    if (n_tokens_max < (int) res.size()) {
        // LLAMA_LOG_ERROR("%s: too many tokens\n", __func__);
        return -((int) res.size());
    }

    for (size_t i = 0; i < res.size(); i++) {
        tokens[i] = res[i];
    }

    return res.size();
}

// ...

int32_t llama_decode(
        struct llama_context * ctx,
          struct llama_batch   batch) {
    const int ret = llama_decode_internal(*ctx, batch);
    if (ret < 0) {
        LLAMA_LOG_ERROR("%s: failed to decode, ret = %d\n", __func__, ret);
    }

    return ret;
}

// decode a batch of tokens by evaluating the transformer
//
//   - lctx:      llama context
//   - batch:     batch to evaluate
//
// return 0 on success
// return positive int on warning
// return negative int on error
//
static int llama_decode_internal(
         llama_context & lctx,
           llama_batch   batch_all) { // TODO: rename back to batch

    const uint32_t n_tokens_all = batch_all.n_tokens;

    if (n_tokens_all == 0) {
        LLAMA_LOG_ERROR("%s: n_tokens == 0", __func__);
        return -1;
    }

    const auto & model   = lctx.model;
    const auto & hparams = model.hparams;
    const auto & cparams = lctx.cparams;

    GGML_ASSERT((!batch_all.token && batch_all.embd) || (batch_all.token && !batch_all.embd)); // NOLINT

    GGML_ASSERT(n_tokens_all <= cparams.n_batch);

    GGML_ASSERT((cparams.causal_attn || cparams.n_ubatch >= n_tokens_all) && "non-causal attention requires n_ubatch >= n_tokens");

    if (lctx.t_compute_start_us == 0) {
        lctx.t_compute_start_us = ggml_time_us();
    }
    lctx.n_queued_tokens += n_tokens_all;

#ifdef GGML_USE_MPI
    // TODO: needs fix after #3228
    GGML_ASSERT(false && "not implemented");
    //ggml_mpi_eval_init(lctx.ctx_mpi, &n_tokens, &n_past, &n_threads);
#endif

    auto & kv_self = lctx.kv_self;

    const int64_t n_embd  = hparams.n_embd;
    const int64_t n_vocab = hparams.n_vocab;

    uint32_t n_outputs = 0;
    uint32_t n_outputs_prev = 0;

    const auto n_ubatch = cparams.n_ubatch;

    std::vector<llama_pos> pos;
    std::vector<int32_t>                   n_seq_id;
    std::vector<llama_seq_id *>            seq_id_arr;
    std::vector<std::vector<llama_seq_id>> seq_id;

    // count outputs
    if (batch_all.logits) {
        for (uint32_t i = 0; i < n_tokens_all; ++i) {
            n_outputs += batch_all.logits[i] != 0;
        }
    } else if (lctx.logits_all || (cparams.embeddings && cparams.pooling_type != LLAMA_POOLING_TYPE_NONE)) {
        n_outputs = n_tokens_all;
    } else {
        // keep last output only
        n_outputs = 1;
    }

    // reserve output buffer
    if (llama_output_reserve(lctx, n_outputs) < n_outputs) {
        LLAMA_LOG_ERROR("%s: could not reserve space for batch with %u outputs\n", __func__, n_outputs);
        return -2;
    };

    // set output mappings
    if (batch_all.logits) {
        int32_t i_logits = 0;
        for (uint32_t i = 0; i < n_tokens_all; ++i) {
            if (batch_all.logits[i]) {
                lctx.output_ids[i] = i_logits++;
            }
        }
    } else {
        for (uint32_t i = 0; i < n_outputs; ++i) {
            lctx.output_ids[i] = i;
        }
    }

    for (uint32_t cur_token = 0; cur_token < n_tokens_all; cur_token += n_ubatch) {
        const uint32_t n_tokens = std::min(n_ubatch, n_tokens_all - cur_token);
        llama_batch u_batch = {
            /* .n_tokens   = */ (int32_t) n_tokens,
            /* .token      = */ batch_all.token     ? batch_all.token    + cur_token        : nullptr,
            /* .embd       = */ batch_all.embd      ? batch_all.embd     + cur_token*n_embd : nullptr,
            /* .pos        = */ batch_all.pos       ? batch_all.pos      + cur_token        : nullptr,
            /* .n_seq_id   = */ batch_all.n_seq_id  ? batch_all.n_seq_id + cur_token        : nullptr,
            /* .seq_id     = */ batch_all.seq_id    ? batch_all.seq_id   + cur_token        : nullptr,
            /* .logits     = */ batch_all.logits    ? batch_all.logits   + cur_token        : nullptr,
            /* .all_pos_0  = */ batch_all.all_pos_0 + (llama_pos) cur_token*batch_all.all_pos_1,
            /* .all_pos_1  = */ batch_all.all_pos_1,
            /* .all_seq_id = */ batch_all.all_seq_id,
        };

        // count the outputs in this u_batch
        {
            int32_t n_outputs_new = 0;

            if (u_batch.logits) {
                for (uint32_t i = 0; i < n_tokens; i++) {
                    n_outputs_new += u_batch.logits[i] != 0;
                }
            } else if (n_outputs == n_tokens_all) {
                n_outputs_new = n_tokens;
            } else {
                // keep last output only
                if (cur_token + n_tokens >= n_tokens_all) {
                    n_outputs_new = 1;
                }
            }

            // needs to happen before the graph is built
            lctx.n_outputs = n_outputs_new;
        }

        int n_threads = n_tokens == 1 ? cparams.n_threads : cparams.n_threads_batch;
        GGML_ASSERT(n_threads > 0);

        // helpers for smoother batch API transition
        // after deprecating the llama_eval calls, these will be removed
        if (u_batch.pos == nullptr) {
            pos.resize(n_tokens);
            for (uint32_t i = 0; i < n_tokens; i++) {
                pos[i] = u_batch.all_pos_0 + i*u_batch.all_pos_1;
            }

            u_batch.pos = pos.data();
        }

        if (u_batch.seq_id == nullptr) {
            n_seq_id.resize(n_tokens);
            seq_id.resize(n_tokens);
            seq_id_arr.resize(n_tokens);
            for (uint32_t i = 0; i < n_tokens; i++) {
                n_seq_id[i] = 1;
                seq_id[i].resize(1);
                seq_id[i][0] = u_batch.all_seq_id;
                seq_id_arr[i] = seq_id[i].data();
            }

            u_batch.n_seq_id = n_seq_id.data();
            u_batch.seq_id = seq_id_arr.data();
        }

        // non-causal masks do not use the KV cache
        if (hparams.causal_attn) {
            llama_kv_cache_update(&lctx);

            // if we have enough unused cells before the current head ->
            //   better to start searching from the beginning of the cache, hoping to fill it
            if (kv_self.head > kv_self.used + 2*n_tokens) {
                kv_self.head = 0;
            }

            if (!llama_kv_cache_find_slot(kv_self, u_batch)) {
                return 1;
            }

            if (!kv_self.recurrent) {
                // a heuristic, to avoid attending the full cache if it is not yet utilized
                // after enough generations, the benefit from this heuristic disappears
                // if we start defragmenting the cache, the benefit from this will be more important
                kv_self.n = std::min(kv_self.size, std::max(32u, GGML_PAD(llama_kv_cache_cell_max(kv_self), 32)));
                //kv_self.n = llama_kv_cache_cell_max(kv_self);
            }
        }

        //printf("kv_self.n = %5d, kv_self.used = %5d, kv_self.head = %5d\n", kv_self.n, kv_self.used, kv_self.head);

        ggml_backend_sched_reset(lctx.sched);
        ggml_backend_sched_set_eval_callback(lctx.sched, lctx.cparams.cb_eval, lctx.cparams.cb_eval_user_data);

        ggml_cgraph * gf = llama_build_graph(lctx, u_batch, false);

        // the output is always the last tensor in the graph
        struct ggml_tensor * res  = gf->nodes[gf->n_nodes - 1];
        struct ggml_tensor * embd = gf->nodes[gf->n_nodes - 2];

        if (lctx.n_outputs == 0) {
            // no output
            res  = nullptr;
            embd = nullptr;
        } else if (!hparams.causal_attn) {
            res = nullptr; // do not extract logits for embedding models such as BERT

            // token or sequence embeddings
            embd = gf->nodes[gf->n_nodes - 1];

            GGML_ASSERT(strcmp(embd->name, "result_embd") == 0 || strcmp(embd->name, "result_embd_pooled") == 0);
        } else if (cparams.embeddings) {
            // the embeddings could be in the second to last tensor, or any of the previous tensors
            int i_embd = gf->n_nodes - 2;
            for (int i = 3; strcmp(embd->name, "result_norm") != 0; ++i) {
                i_embd = gf->n_nodes - i;
                if (i_embd < 0) { break; }
                embd = gf->nodes[i_embd];
            }
            GGML_ASSERT(i_embd >= 0 && "missing result_norm tensor");

            // TODO: use a per-batch flag to know when to skip logits while keeping embeddings
            if (!cparams.causal_attn) {
                res = nullptr; // do not extract logits when not needed
                // skip computing logits
                // TODO: is this safe?
                gf->n_nodes = i_embd + 1;
            }
        } else {
            embd = nullptr; // do not extract embeddings when not needed
            GGML_ASSERT(strcmp(res->name, "result_output") == 0 && "missing result_output tensor");
        }
        // LLAMA_LOG_INFO("graph build time: %.3f ms (%d nodes, %d leafs)\n", (ggml_time_us() - t_start_us)/1000.0, gf->n_nodes, gf->n_leafs);

        // for big prompts, if BLAS is enabled, it is better to use only one thread
        // otherwise, the threads are spin-lock waiting for the BLAS calls and are degrading the performance
        // TODO: this is mostly important for Apple Silicon where CBLAS is still performing very well
        //       we still need some threads to process all non-mul_mat ops, but not too much to avoid interfering
        //       with the BLAS calls. need a better solution
        // MoE Special Case: This logic applies when hparams.n_expert == 0, i.e. the model is NOT an MoE model. When an MoE is
        //                   being processed then Accelerate/BLAS will not be involved, so capping would limit performance.
        if (n_tokens >= 32 && hparams.n_expert == 0 && ggml_cpu_has_blas() && !ggml_cpu_has_gpublas()) {
            n_threads = std::min(4, n_threads);
        }

        ggml_backend_sched_alloc_graph(lctx.sched, gf);

        llama_set_inputs(lctx, u_batch);

        llama_graph_compute(lctx, gf, n_threads);

        // update the kv ring buffer
        {
            kv_self.head += n_tokens;

            // Ensure kv cache head points to a valid index.
            if (kv_self.head >= kv_self.size) {
                kv_self.head = 0;
            }
        }

#ifdef GGML_PERF
        // print timing information per ggml operation (for debugging purposes)
        // requires GGML_PERF to be defined
        ggml_graph_print(gf);
#endif

        // plot the computation graph in dot format (for debugging purposes)
        //if (n_past%100 == 0) {
        //    ggml_graph_dump_dot(gf, NULL, "llama.dot");
        //}

        // extract logits
        if (res) {
            ggml_backend_t backend_res = ggml_backend_sched_get_tensor_backend(lctx.sched, res);
            GGML_ASSERT(backend_res != nullptr);
            GGML_ASSERT(lctx.logits != nullptr);

            float * logits_out = lctx.logits + n_outputs_prev*n_vocab;
            const int32_t n_outputs_new = lctx.n_outputs;

            if (n_outputs_new) {
                GGML_ASSERT( n_outputs_prev + n_outputs_new <= n_outputs);
                GGML_ASSERT((n_outputs_prev + n_outputs_new)*n_vocab <= (int64_t) lctx.logits_size);
                ggml_backend_tensor_get_async(backend_res, res, logits_out, 0, n_outputs_new*n_vocab*sizeof(float));
            }
        }

        // extract embeddings
        if (embd) {
            ggml_backend_t backend_embd = ggml_backend_sched_get_tensor_backend(lctx.sched, embd);
            GGML_ASSERT(backend_embd != nullptr);

            switch (cparams.pooling_type) {
                case LLAMA_POOLING_TYPE_NONE:
                    {
                        // extract token embeddings
                        GGML_ASSERT(lctx.embd != nullptr);
                        float * embd_out = lctx.embd + n_outputs_prev*n_embd;
                        const int32_t n_outputs_new = lctx.n_outputs;

                        if (n_outputs_new) {
                            GGML_ASSERT( n_outputs_prev + n_outputs_new <= n_outputs);
                            GGML_ASSERT((n_outputs_prev + n_outputs_new)*n_embd <= (int64_t) lctx.embd_size);
                            ggml_backend_tensor_get_async(backend_embd, embd, embd_out, 0, n_outputs_new*n_embd*sizeof(float));
                        }
                    } break;
                case LLAMA_POOLING_TYPE_CLS:
                case LLAMA_POOLING_TYPE_MEAN:
                    {
                        GGML_ASSERT(strcmp(embd->name, "result_embd_pooled") == 0);

                        // extract sequence embeddings
                        auto & embd_seq_out = lctx.embd_seq;
                        embd_seq_out.clear();

                        for (uint32_t i = 0; i < n_tokens; i++) {
                            const llama_seq_id seq_id = u_batch.seq_id[i][0];
                            if (embd_seq_out.find(seq_id) != embd_seq_out.end()) {
                                continue;
                            }
                            embd_seq_out[seq_id].resize(n_embd);
                            ggml_backend_tensor_get_async(backend_embd, embd, embd_seq_out[seq_id].data(), (n_embd*seq_id)*sizeof(float), n_embd*sizeof(float));
                        }
                    } break;
                case LLAMA_POOLING_TYPE_UNSPECIFIED:
                    {
                        GGML_ASSERT(false && "unknown pooling type");
                    } break;
            }
        }
        n_outputs_prev += lctx.n_outputs;
    }

    // set to total number of outputs in the batch, for use in llama_get_logits_ith
    lctx.n_outputs = n_outputs;

    // wait for the computation to finish (automatically done when obtaining the model output)
    //llama_synchronize(&lctx);

    // decide if we need to defrag the kv cache
    if (cparams.causal_attn && cparams.defrag_thold >= 0.0f) {
        const float fragmentation = kv_self.n >= 128 ? 1.0f - float(kv_self.used)/float(kv_self.n) : 0.0f;

        // queue defragmentation for next llama_kv_cache_update
        if (fragmentation > cparams.defrag_thold) {
            //LLAMA_LOG_INFO("fragmentation: %.2f\n", fragmentation);

            llama_kv_cache_defrag(kv_self);
        }
    }

    return 0;
}
```

```
static void llama_graph_compute(
        llama_context & lctx,
          ggml_cgraph * gf,
                  int   n_threads) {
#ifdef GGML_USE_MPI
    const int64_t n_layer = lctx.model.hparams.n_layer;
    ggml_mpi_graph_compute_pre(lctx.ctx_mpi, gf, n_layer);
#endif

#ifdef GGML_USE_METAL
    if (ggml_backend_is_metal(lctx.backend_metal)) {
        ggml_backend_metal_set_n_cb(lctx.backend_metal, n_threads);
    }
#endif

    if (lctx.backend_cpu != nullptr) {
        ggml_backend_cpu_set_n_threads(lctx.backend_cpu, n_threads);
        ggml_backend_cpu_set_abort_callback(lctx.backend_cpu, lctx.abort_callback, lctx.abort_callback_data);
    }

    ggml_backend_sched_graph_compute_async(lctx.sched, gf);

    // fprintf(stderr, "splits: %d\n", ggml_backend_sched_get_n_splits(lctx.sched));

#ifdef GGML_USE_MPI
    ggml_mpi_graph_compute_post(lctx.ctx_mpi, gf, n_layer);
#endif
}

// ...

static void llama_set_inputs(llama_context & lctx, const llama_batch & batch) {
    //
    // set input data
    //

    const auto & hparams = lctx.model.hparams;
    const auto & cparams = lctx.cparams;
    const auto & kv_self = lctx.kv_self;

    if (batch.token) {
        const int64_t n_tokens = batch.n_tokens;

        ggml_backend_tensor_set(lctx.inp_tokens, batch.token, 0, n_tokens*ggml_element_size(lctx.inp_tokens));
    }

    if (batch.embd) {
        const int64_t n_embd   = hparams.n_embd;
        const int64_t n_tokens = batch.n_tokens;

        ggml_backend_tensor_set(lctx.inp_embd, batch.embd, 0, n_tokens*n_embd*ggml_element_size(lctx.inp_embd));
    }

    if (batch.pos && lctx.inp_pos) {
        const int64_t n_tokens = batch.n_tokens;

        ggml_backend_tensor_set(lctx.inp_pos, batch.pos, 0, n_tokens*ggml_element_size(lctx.inp_pos));
    }

    if (hparams.causal_attn || cparams.pooling_type == LLAMA_POOLING_TYPE_NONE) {
        GGML_ASSERT(lctx.inp_out_ids && "every model that can must skip unused outputs");
        const int64_t n_tokens = batch.n_tokens;

        GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_out_ids->buffer));
        int32_t * data = (int32_t *) lctx.inp_out_ids->data;

        if (lctx.n_outputs == n_tokens) {
            for (int i = 0; i < n_tokens; ++i) {
                data[i] = i;
            }
        } else if (batch.logits) {
            int32_t n_outputs = 0;
            for (int i = 0; i < n_tokens; ++i) {
                if (batch.logits[i]) {
                    data[n_outputs++] = i;
                }
            }
            // the graph needs to have been passed the correct number of outputs
            GGML_ASSERT(lctx.n_outputs == n_outputs);
        } else if (lctx.n_outputs == 1) {
            // only keep last output
            data[0] = n_tokens - 1;
        } else {
            GGML_ASSERT(lctx.n_outputs == 0);
        }
    }

    GGML_ASSERT(
        // (!a || b) is a logical implication (a -> b)
        // !hparams.causal_attn -> !cparams.causal_attn
        (hparams.causal_attn || !cparams.causal_attn) &&
        "causal attention with embedding models is not supported"
    );

    if (lctx.inp_KQ_mask) {
        // NOTE: hparams.causal_attn indicates the model is capable of generation and uses the kv cache.
        if (cparams.causal_attn) {
            const int64_t n_kv     = kv_self.n;
            const int64_t n_tokens = batch.n_tokens;

            GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_KQ_mask->buffer));

            float * data = (float *) lctx.inp_KQ_mask->data;

            // For causal attention, use only the previous KV cells
            // of the correct sequence for each token of the batch.
            // It's assumed that if a token in the batch has multiple sequences, they are equivalent.
            for (int h = 0; h < 1; ++h) {
                for (int j = 0; j < n_tokens; ++j) {
                    const llama_pos    pos    = batch.pos[j];
                    const llama_seq_id seq_id = batch.seq_id[j][0];

                    for (int i = 0; i < n_kv; ++i) {
                        float f;
                        if (!lctx.kv_self.cells[i].has_seq_id(seq_id) || lctx.kv_self.cells[i].pos > pos) {
                            f = -INFINITY;
                        } else {
                            f = 0.0f;
                        }
                        data[h*(n_kv*n_tokens) + j*n_kv + i] = f;
                    }
                }
            }
        } else {
            // when using kv cache, the mask needs to match the kv cache size
            const int64_t n_tokens = batch.n_tokens;
            const int64_t n_stride = hparams.causal_attn ? kv_self.n : n_tokens;

            GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_KQ_mask->buffer));

            float * data = (float *) lctx.inp_KQ_mask->data;

            for (int h = 0; h < 1; ++h) {
                for (int j = 0; j < n_tokens; ++j) {
                    const llama_seq_id seq_id = batch.seq_id[j][0];

                    for (int i = 0; i < n_tokens; ++i) {
                        float f = -INFINITY;
                        for (int s = 0; s < batch.n_seq_id[i]; ++s) {
                            if (batch.seq_id[i][s] == seq_id) {
                                f = 0.0f;
                                break;
                            }
                        }

                        data[h*(n_tokens*n_tokens) + j*n_stride + i] = f;
                    }

                    for (int i = n_tokens; i < n_stride; ++i) {
                        data[h*(n_tokens*n_tokens) + j*n_stride + i] = -INFINITY;
                    }
                }
            }
        }
    }

    if (hparams.need_kq_pos) {
        const int64_t n_kv = kv_self.n;

        GGML_ASSERT(lctx.inp_KQ_pos);
        GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_KQ_pos->buffer));

        float * data = (float *) lctx.inp_KQ_pos->data;

        for (int i = 0; i < n_kv; ++i) {
            data[i] = float(lctx.kv_self.cells[i].pos);
        }
    }

    if (cparams.pooling_type == LLAMA_POOLING_TYPE_MEAN) {
        const int64_t n_tokens = batch.n_tokens;

        GGML_ASSERT(lctx.inp_mean);
        GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_mean->buffer));

        float * data = (float *) lctx.inp_mean->data;
        memset(lctx.inp_mean->data, 0, n_tokens * n_tokens * ggml_element_size(lctx.inp_mean));

        std::vector<uint64_t> sum(n_tokens, 0);
        for (int i = 0; i < n_tokens; ++i) {
            const llama_seq_id seq_id = batch.seq_id[i][0];

            GGML_ASSERT(seq_id < n_tokens && "seq_id cannot be larger than n_tokens with pooling_type == MEAN");

            sum[seq_id] += 1;
        }

        std::vector<float> div(n_tokens, 0.0f);
        for (int i = 0; i < n_tokens; ++i) {
            const uint64_t s = sum[i];
            if (s > 0) {
                div[i] = 1.0f/float(s);
            }
        }

        for (int i = 0; i < n_tokens; ++i) {
            const llama_seq_id seq_id = batch.seq_id[i][0];
            data[seq_id*n_tokens + i] = div[seq_id];
        }
    }

    if (cparams.pooling_type == LLAMA_POOLING_TYPE_CLS) {
        const int64_t n_tokens = batch.n_tokens;

        GGML_ASSERT(lctx.inp_cls);
        GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_cls->buffer));

        uint32_t * data = (uint32_t *) lctx.inp_cls->data;
        memset(lctx.inp_cls->data, 0, n_tokens * ggml_element_size(lctx.inp_cls));

        for (int i = 0; i < n_tokens; ++i) {
            const llama_seq_id seq_id = batch.seq_id[i][0];
            const llama_pos    pos    = batch.pos[i];

            GGML_ASSERT(seq_id < n_tokens && "seq_id cannot be larger than n_tokens with pooling_type == CLS");

            if (pos == 0) {
                data[seq_id] = i;
            }
        }
    }

    if (kv_self.recurrent) {
        const int64_t n_kv = kv_self.n;

        if (lctx.inp_s_mask) {
            GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_s_mask->buffer));
            float * data = (float *) lctx.inp_s_mask->data;

            // states which are not affected by the current batch are left untouched
            for (int i = 0; i < n_kv; ++i) {
                llama_seq_id    seq_id       = i + lctx.kv_self.head;
                llama_kv_cell & kv_cell      = lctx.kv_self.cells[seq_id];
                bool            has_self_seq = kv_cell.has_seq_id(seq_id);

                data[i] = (float) has_self_seq;

                // ensure current sequences will be kept
                if (!has_self_seq && kv_cell.pos >= 0) {
                    kv_cell.seq_id.insert(seq_id);
                }
            }
        }
        // For Mamba (and other recurrent architectures),
        // update the correct state(s)/sequence(s) for each token of the batch.
        // Like with the KQ_mask, if a token in the batch has multiple sequences,
        // they are assumed to be equivalent (not here, but in ggml_ssm_scan and ggml_ssm_conv).
        if (lctx.inp_s_seq) {
            const int64_t n_tokens = batch.n_tokens;

            GGML_ASSERT(ggml_backend_buffer_is_host(lctx.inp_s_seq->buffer));
            int32_t * data = (int32_t *) lctx.inp_s_seq->data;

            for (int j = 0; j < n_tokens; ++j) {
                const int32_t n_seq = batch.n_seq_id[j];
                GGML_ASSERT(0 < n_seq); // a token should be part of at least 1 sequence

                for (int i = 0; i < n_kv; ++i) {
                    if (i < n_seq) {
                        // for this type of model, the head is the minimum seq_id of the batch
                        data[j*n_kv + i] = batch.seq_id[j][i] - kv_self.head;
                    } else {
                        data[j*n_kv + i] = -1;
                    }
                }
            }
        }
    }
}
```
