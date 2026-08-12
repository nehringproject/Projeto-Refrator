from setuptools import find_packages, setup


setup(
    name="tiktoken",
    version="0.12.0+refrator.1",
    description="Refrator conservative Android tokenizer shim for LiteLLM",
    packages=find_packages(),
    python_requires=">=3.9",
    license="Apache-2.0",
)
