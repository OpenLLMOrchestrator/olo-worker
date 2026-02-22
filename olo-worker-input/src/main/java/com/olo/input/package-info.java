/**
 * OLO worker input module: workflow input model, consumer contract, and producer builder.
 *
 * <ul>
 *   <li>{@link com.olo.input.model} – payload DTOs and enums (WorkflowInput, InputItem, Storage, Context, Routing, Metadata, etc.)</li>
 *   <li>{@link com.olo.input.consumer} – read-only contract ({@link com.olo.input.consumer.WorkflowInputValues}) and resolution (DefaultWorkflowInputValues, CacheReader, FileReader)</li>
 *   <li>{@link com.olo.input.producer} – builder and cache write contract (WorkflowInputProducer, CacheWriter, InputStorageKeys)</li>
 *   <li>{@link com.olo.input.config} – configuration (MaxLocalMessageSize)</li>
 * </ul>
 */
package com.olo.input;
