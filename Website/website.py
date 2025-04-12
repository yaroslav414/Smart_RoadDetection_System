from flask import Flask, render_template

app = Flask(__name__)

@app.route('/')
def home():
    return render_template('index.html')

@app.route('/team')
def team():
    return render_template('team.html')

@app.route('/contact')
def contact():
    return render_template('Contact.html')

if __name__ == '__main__':
    app.run(host='0.0.0.0', port = 5000, debug=True)
