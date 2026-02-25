package com.olo.plugin;

import com.olo.config.TenantConfig;

import java.util.Map;

/**
 * Contract for an image generation plugin (e.g. Stable Diffusion, ComfyUI, InvokeAI).
 * Aligns with {@link ContractType#IMAGE_GENERATOR}. Invoked via
 * {@link #execute(Map, TenantConfig)} with "prompt" and optional options.
 * <p>
 * Input map:
 * <ul>
 *   <li>"prompt" (String) – text prompt</li>
 *   <li>"negativePrompt" (String, optional)</li>
 *   <li>"width" (Integer, optional)</li>
 *   <li>"height" (Integer, optional)</li>
 *   <li>"steps" (Integer, optional)</li>
 *   <li>"seed" (Long, optional)</li>
 * </ul>
 * Output map:
 * <ul>
 *   <li>"imageUrl" (String) – URL to the generated image, or</li>
 *   <li>"imageBase64" (String) – base64-encoded image data</li>
 *   <li>"seed" (Long, optional)</li>
 * </ul>
 */
public interface ImageGenerationPlugin extends ExecutablePlugin {
}
