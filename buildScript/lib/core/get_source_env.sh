if [ ! -z $ENV_NB4A ]; then
  export COMMIT_SING_BOX_EXTRA="66e9fa6b85443e5a9d62bcffa2efadc1c033e912"
fi

if [ ! -z $ENV_SING_BOX_EXTRA ]; then
  source libs/get_source_env.sh
  # export COMMIT_SING_BOX="91495e813068294aae506fdd769437c41dd8d3a3"
fi
