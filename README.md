# Run on Heroku

    heroku create
    heroku addons:add cloudamqp
    git push heroku master
    heroku scale scheduler=1
    heroku scale worker=2
    heroku logs -t