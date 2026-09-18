"""Text embeddings from the embeddings service: BGE-M3 over HTTP (Text Embeddings Inference).

It runs as a container rather than in-process because current PyTorch and ONNX Runtime ship no
Intel-Mac builds. Phase 1's description-similarity check uses it now; Phase 4 retrieval uses it for
every query.
"""
